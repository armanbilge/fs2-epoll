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

import jnr.ffi.{LibraryLoader, Platform, Pointer, Struct, Runtime, LastError, Memory, Union}

private[epoll] object libc {

  object jnr {
    lazy val globalRuntime = Runtime.getSystemRuntime

    private lazy val libc = LibraryLoader
      .create(classOf[jnrLibcFFIInterface])
      .load(Platform.getNativePlatform().getStandardCLibraryName())

    final class EpollData(runtime: Runtime) extends Union(runtime) {
      final val ptr: PointerField = new Pointer()
      final val fd: Signed32 = new Signed32()
      final val u32: Unsigned32 = new Unsigned32()
      final val u64: Unsigned64 = new Unsigned64()
    }

    final class EpollEvent(runtime: Runtime) extends Struct(runtime) {
      final private val _events: Unsigned32 = new Unsigned32()
      final private val _data: EpollData = inner(new EpollData(runtime))

      @inline
      final def events(): Int = _events.get().toInt
      @inline
      final def fd(): Int = _data.fd.get().toInt
    }

    object EpollEvent {
      final val CStructSize: Int = 12

      def apply(events: Int, data: Int, runtime: Runtime): EpollEvent = {
        val ev = new EpollEvent(runtime)
        ev._events.set(events.toLong)
        ev._data.fd.set(data)
        ev
      }

      def apply(ptr: Pointer, offset: Long, runtime: Runtime): EpollEvent = {
        val dest = new Array[Byte](CStructSize)
        ptr.get(
          offset,
          dest,
          0,
          CStructSize
        )

        val event =
          dest
            .take(4)
            .reverse
            .foldLeft(0)((acc, b) => (acc << 8) | (b & 0xff))

        val data =
          dest
            .drop(8)
            .take(4)
            .reverse
            .foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff))

        val epollEvent = new EpollEvent(runtime)

        epollEvent._data.fd.set(data.toInt)
        epollEvent._events.set(event.toLong)

        epollEvent
      }
    }

    private trait jnrLibcFFIInterface {
      def epoll_create1(flags: Int): Int
      def epoll_ctl(epfd: Int, op: Int, fd: Int, event: EpollEvent): Int
      def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int
      def errno(): Int
      def strerror(errnum: Int): String
      def close(fd: Int): Int
      def eventfd(initval: Int, flags: Int): Int
      def write(fd: Int, buf: Pointer, count: Long): Long
      def read(fd: Int, buf: Pointer, count: Long): Long
      def pipe(pipefd: Array[Int]): Int
      def fcntl(fd: Int, cmd: Int): Int
    }

    def epoll_create1(flags: Int): Int = libc.epoll_create1(flags)
    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: EpollEvent): Int =
      libc.epoll_ctl(epfd, op, fd, event)
    def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int =
      libc.epoll_wait(epfd, events, maxevents, timeout)
    def errno(): Int = LastError.getLastError(globalRuntime)
    def strerror(errnum: Int): String = libc.strerror(errnum)
    def close(fd: Int): Int = libc.close(fd)
    def eventfd(initval: Int, flags: Int): Int = libc.eventfd(initval, flags)
    def write(fd: Int, buf: Pointer, count: Long): Long = libc.write(fd, buf, count)
    def read(fd: Int, buf: Pointer, count: Long): Long = libc.read(fd, buf, count)
    def pipe(pipefd: Array[Int]): Int = libc.pipe(pipefd)
    def fcntl(fd: Int, cmd: Int): Int = libc.fcntl(fd, cmd)

    def byteToPtr(bytes: Array[Byte]): Pointer = {
      val buf = Memory.allocateDirect(globalRuntime, bytes.length)
      buf.put(0, bytes, 0, bytes.length)
      buf
    }
  }
}
