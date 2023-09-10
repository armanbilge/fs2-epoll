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

import cats.effect.{IO, Resource, FileDescriptorPoller, FileDescriptorPollHandle}
import cats.effect.std.Mutex
import cats.syntax.all._
import cats.effect.unsafe.PollingSystem

import java.io.IOException
import java.util.{Collections, IdentityHashMap, Set}

object EpollSystem extends PollingSystem {
  import epoll._
  import libc.jnr._

  type Api = FileDescriptorPoller

  def close(): Unit = ()

  def makeApi(register: (Poller => Unit) => Unit): Api =
    new FileDescriptorPollerImpl(register)

  def makePoller(): Poller = {
    val fd = epoll_create1(0)
    if (fd == -1)
      throw new IOException(strerror(errno()))
    new Poller(fd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

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
    private[this] var readReadyCounter = 0
    private[this] var readCallback: Either[Throwable, Int] => Unit = null

    private[this] var writeReadyCounter = 0
    private[this] var writeCallback: Either[Throwable, Int] => Unit = null

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

  final class Poller private[EpollSystem] (epfd: Int) {

    private[this] val handles: Set[PollHandle] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[EpollSystem] def close(): Unit =
      if (libc.jnr.close(epfd) != 0)
        throw new IOException(strerror(errno()))

    private[EpollSystem] def poll(timeout: Long): Boolean = ???

    private[EpollSystem] def needsPoll(): Boolean = !handles.isEmpty()

    private[EpollSystem] def register(
        fd: Int,
        reads: Boolean,
        writes: Boolean,
        handle: PollHandle,
        cb: Either[Throwable, (PollHandle, IO[Unit])] => Unit
    ): Unit = ???
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
