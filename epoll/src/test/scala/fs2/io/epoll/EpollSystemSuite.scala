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

import cats.effect.{IO, Resource}
import cats.syntax.all._

import fs2.io.epoll.unsafe.EpollSystem._
import fs2.io.epoll.unsafe.libc.jnr._
import fs2.io.epoll.unsafe.libc

import java.io.IOException

import jnr.constants.platform.{Errno, Fcntl}
import jnr.ffi.{Memory, Pointer}

class EpollSystemSuite extends EpollSuite {

  final class Pipe(
      val readFd: Int,
      val writeFd: Int,
      val readHandle: FileDescriptorPollHandle,
      val writeHandle: FileDescriptorPollHandle
  ) {

    def read(buf: Array[Byte], offset: Int, length: Int): IO[Unit] =
      allocateMemory(length).flatMap { ptr =>
        readHandle
          .pollReadRec(()) { _ =>
            IO(guard(libc.jnr.read(readFd, ptr, length.toLong).toInt)) <* ptrToBuf(
              buf,
              ptr,
              length,
              offset
            )
          }
          .void
      }

    def write(buf: Array[Byte], length: Int): IO[Unit] =
      writeHandle
        .pollWriteRec(()) { _ =>
          IO(guard(libc.jnr.write(writeFd, byteToPtr(buf), length.toLong).toInt))
        }
        .void

    private def guard(thunk: => Int): Either[Unit, Int] = {
      val rtn = thunk
      if (rtn < 0) {
        val en = errno()
        if (en == Errno.EAGAIN.intValue() || en == Errno.EWOULDBLOCK.intValue())
          Left(())
        else
          throw new IOException(strerror(errno()))
      } else
        Right(rtn)
    }

    private def ptrToBuf(buf: Array[Byte], ptr: Pointer, length: Int, offset: Int): IO[Unit] =
      IO {
        (0 until length).foreach { i =>
          buf(offset + i) = ptr.getByte(i.toLong)
        }
      }

    private def allocateMemory(size: Int): IO[Pointer] = IO(Memory.allocate(globalRuntime, size))
  }

  def getPoller(): IO[Poller] =
    IO.pollers.map(_.collectFirst { case epoll: Poller => epoll }).map(_.get)

  def mkPipe: Resource[IO, Pipe] =
    Resource
      .make {
        IO {
          val fd = new Array[Int](2)
          if (libc.jnr.pipe(fd) != 0)
            throw new IOException(strerror(errno()))
          (fd(0), fd(1))
        }
      } { case (readFd, writeFd) =>
        IO {
          libc.jnr.close(readFd)
          libc.jnr.close(writeFd)
          ()
        }
      }
      .evalTap { case (readFd, writeFd) =>
        IO {
          if (
            libc.jnr.fcntl(
              readFd,
              Fcntl.F_SETFL.intValue()
              // TODO: is this flag needed?
              // OpenFlags.O_NONBLOCK.intValue()
            ) != 0
          )
            throw new IOException(strerror(errno()))
          if (
            libc.jnr.fcntl(
              writeFd,
              Fcntl.F_SETFL.intValue()
              // TODO: is this flag needed?
              // OpenFlags.O_NONBLOCK.intValue()
            ) != 0
          )
            throw new IOException(strerror(errno()))
        }
      }
      .flatMap { case (readFd, writeFd) =>
        Resource.eval(FileDescriptorPoller.get).flatMap { poller =>
          (
            poller.registerFileDescriptor(readFd, true, false),
            poller.registerFileDescriptor(writeFd, false, true)
          ).mapN(new Pipe(readFd, writeFd, _, _))
        }
      }

  test("EpollSystem notify read-ready events") {
    mkPipe.use { pipe =>
      (for {
        buf <- IO(new Array[Byte](4))
        _ <- pipe.write(Array[Byte](1, 2, 3), 3).background.surround(pipe.read(buf, 0, 3))
        _ <- pipe.write(Array[Byte](42), 1).background.surround(pipe.read(buf, 3, 1))
      } yield buf.toList).assertEquals(List[Byte](1, 2, 3, 42))
    }
  }

  test("doing read/write many times") {
    val n = 100
    mkPipe.use { pipe =>
      (for {
        buf <- IO(new Array[Byte](n))
        _ <- pipe.write(Array[Byte](1), 1).replicateA(n)
        _ <- (0 until n).toList.traverse(i => pipe.read(buf, i, 1))
      } yield buf.toList).assertEquals(List.fill[Byte](n)(1))
    }
  }
}
