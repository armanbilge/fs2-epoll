/*
 * Copyright 2023 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.io.epoll
package unsafe

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all._
import cats.effect.unsafe.PollingSystem

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

import jnr.ffi.Memory;

import scala.annotation.tailrec

object EpollSystem extends PollingSystem {

  import epoll._
  import libc.jnr._

  private[this] final val MaxEvents = 64

  type Api = FileDescriptorPoller

  def close(): Unit = ()

  def makeApi(register: (Poller => Unit) => Unit): Api =
    new FileDescriptorPollerImpl(register)

  def makePoller(): Poller = {
    val epfd = epoll_create1(0)
    if (epfd == -1)
      throw new IOException(strerror(errno()))

    val evfd = libc.jnr.eventfd(0, 0)
    if (evfd == -1)
      throw new IOException(strerror(errno()))

    val event = EpollEvent(
      EPOLLIN | EPOLLOUT,
      evfd,
      globalRuntime
    )

    if (epoll_ctl(epfd, EPOLL_CTL_ADD, evfd, event) != 0)
      throw new IOException(strerror(errno()))

    new Poller(epfd, evfd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    targetPoller.interrupt()

  private final class FileDescriptorPollerImpl private[EpollSystem] (
      register: (Poller => Unit) => Unit
  ) extends FileDescriptorPoller {
    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource {
        (Mutex[IO], Mutex[IO]).flatMapN { (readMutex, writeMutex) =>
          IO.async_[(PollHandle, IO[Unit])] { cb =>
            register { epoll =>
              val handle = new PollHandle(readMutex, writeMutex)
              epoll.register(fd, reads, writes, handle, cb)
            }
          }
        }
      }
  }

  private final class PollHandle(
      readMutex: Mutex[IO],
      writeMutex: Mutex[IO]
  ) extends FileDescriptorPollHandle {
    @volatile private[this] var readReadyCounter = 0
    @volatile private[this] var readCallback: Either[Throwable, Int] => Unit = null

    @volatile private[this] var writeReadyCounter = 0
    @volatile private[this] var writeCallback: Either[Throwable, Int] => Unit = null

    def notify(events: Int): Unit = {
      if ((events & EPOLLIN) != 0) {
        val counter = readReadyCounter + 1
        readReadyCounter = counter
        val cb = readCallback
        readCallback = null
        if (cb ne null) cb(Right(counter))
      }
      if ((events & EPOLLOUT) != 0) {
        val counter = writeReadyCounter + 1
        writeReadyCounter = counter
        val cb = writeCallback
        writeCallback = null
        if (cb ne null) cb(Right(counter))
      }
    }

    def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      readMutex.lock.surround {
        def go(a: A, before: Int): IO[B] =
          f(a).flatMap {
            case Left(a) =>
              IO(readReadyCounter).flatMap { after =>
                if (before != after)
                  // there was a read-ready notification since we started, try again immediately
                  go(a, after)
                else
                  IO.asyncCheckAttempt[Int] { cb =>
                    IO {
                      readCallback = cb
                      // check again before we suspend
                      val now = readReadyCounter
                      if (now != before) {
                        readCallback = null
                        Right(now)
                      } else Left(Some(IO(this.readCallback = null)))
                    }
                  }.flatMap(go(a, _))
              }
            case Right(b) => IO.pure(b)
          }

        IO(readReadyCounter).flatMap(go(a, _))
      }

    def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      writeMutex.lock.surround {
        def go(a: A, before: Int): IO[B] =
          f(a).flatMap {
            case Left(a) =>
              IO(writeReadyCounter).flatMap { after =>
                if (before != after)
                  // there was a write-ready notification since we started, try again immediately
                  go(a, after)
                else
                  IO.asyncCheckAttempt[Int] { cb =>
                    IO {
                      writeCallback = cb
                      // check again before we suspend
                      val now = writeReadyCounter
                      if (now != before) {
                        writeCallback = null
                        Right(now)
                      } else Left(Some(IO(this.writeCallback = null)))
                    }
                  }.flatMap(go(a, _))
              }
            case Right(b) => IO.pure(b)
          }

        IO(writeReadyCounter).flatMap(go(a, _))
      }
  }

  final class Poller private[EpollSystem] (epfd: Int, evfd: Int) {

    private final lazy val readBuf = Memory.allocate(globalRuntime, 8)
    private final lazy val writeBuf = {
      val bytes = Array.apply[Byte](0, 0, 0, 0, 0, 0, 0, 1)
      val buf = Memory
        .allocate(globalRuntime, 8)
      buf.put(0, bytes, 0, 8)
      buf
    }

    private[this] val handles: ConcurrentHashMap[Int, PollHandle] = new ConcurrentHashMap()

    private[EpollSystem] def close(): Unit =
      if (libc.jnr.close(epfd) != 0)
        throw new IOException(strerror(errno()))

    private[EpollSystem] def poll(timeout: Long): Boolean = {

      val events = Memory.allocate(globalRuntime, EpollEvent.CStructSize * MaxEvents)
      var polled = false

      @tailrec
      def processEvents(timeout: Int): Unit = {

        val triggeredEvents = epoll_wait(epfd, events, MaxEvents, timeout)

        if (triggeredEvents >= 0) {

          var i = 0
          while (i < triggeredEvents) {
            val epollEvent =
              EpollEvent.apply(events, (i * EpollEvent.CStructSize).toLong, globalRuntime)

            val handle = if (epollEvent.fd() != evfd) {
              // We poll something.
              handles.get(epollEvent.fd())
            } else {
              // We received an interruption.
              // Initialize the eventfd counter to 0.
              if (read(evfd, readBuf, 8) == -1)
                throw new IOException(strerror(errno()))

              null
            }

            // While polling, another worker thread may execute the PollHandle finalizer
            // due to cancellation, and the target PollHandle may not exist in the `handles`.
            // We execute `notify` method only if an entry exists in the HashMap.
            if (handle ne null) {
              handle.notify(epollEvent.events())
              polled = true
            }

            i += 1
          }
        }

        if (triggeredEvents >= MaxEvents)
          processEvents(0) // drain the ready list
        else
          ()
      }

      val timeoutMillis = if (timeout == -1) -1 else (timeout / 1000000).toInt
      processEvents(timeoutMillis)

      polled
    }

    private[EpollSystem] def needsPoll(): Boolean = !handles.isEmpty

    private[EpollSystem] def register(
        fd: Int,
        reads: Boolean,
        writes: Boolean,
        handle: PollHandle,
        cb: Either[Throwable, (PollHandle, IO[Unit])] => Unit
    ): Unit = {
      val events = (EPOLLET | (if (reads) EPOLLIN else 0) | (if (writes) EPOLLOUT else 0)).toInt
      val data = fd
      val event = EpollEvent(events, data, globalRuntime)

      val result =
        if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, event) != 0)
          Left(new IOException(strerror(errno())))
        else {
          handles.put(data, handle)
          val remove = IO {
            handles.remove(data)
            if (epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null) != 0)
              throw new IOException(strerror(errno()))
          }
          Right((handle, remove))
        }

      cb(result)
    }

    private[EpollSystem] def interrupt(): Unit =
      if (write(evfd, writeBuf, 8) < 0)
        throw new IOException(strerror(errno()))

  }

  private object epoll {
    final val EPOLL_CTL_ADD = 1
    final val EPOLL_CTL_DEL = 2
    final val EPOLL_CTL_MOD = 3

    final val EPOLLIN = 0x001
    final val EPOLLOUT = 0x004
    final val EPOLLONESHOT = 1 << 30
    final val EPOLLET = 1 << 31
  }

}
