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

import jnr.ffi.{LibraryLoader, Platform, Pointer, Struct, Runtime, LastError}

private[epoll] object libc {

  object jnr {
    lazy val globalRuntime = Runtime.getSystemRuntime

    private lazy val libc = LibraryLoader
      .create(classOf[jnrLibcFFIInterface])
      .load(Platform.getNativePlatform().getStandardCLibraryName())

    /*
     *  epoll_event layout
     *
     *  +---------------------+---------------------+---------------------+
     *  |       events        |       padding       |       data          |
     *  |       32bit         |       32bit         |       64bit         |
     *  +---------------------+---------------------+---------------------+
     *  Total size: 16 bytes
     */
    final class EpollEvent(runtime: Runtime) extends Struct(runtime) {
      final private val _events: Unsigned32 = new Unsigned32()
      final private val _data: Unsigned64 = new Unsigned64()

      final val events: Int = _events.get().toInt
      final val data: Long = _data.get()
    }

    object EpollEvent {
      final val CStructSize: Int = 16

      def apply(events: Int, data: Long, runtime: Runtime): EpollEvent = {
        val ev = new EpollEvent(runtime)
        ev._events.set(events.toLong)
        ev._data.set(data)
        ev
      }

      def apply(ptr: Pointer, offset: Long, runtime: Runtime): EpollEvent = {
        val dest = Array.emptyByteArray
        ptr.get(
          offset,
          dest,
          0,
          CStructSize
        )

        val (eventField, dataField) = dest.splitAt(EpollEvent.CStructSize / 2)
        val event =
          eventField
            .take(4)
            .foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff))

        val data =
          dataField
            .take(8)
            .foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff))

        val epollEvent = new EpollEvent(runtime)

        epollEvent._data.set(data)
        epollEvent._events.set(event)

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
    }

    def epoll_create1(flags: Int): Int = libc.epoll_create1(flags)
    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: EpollEvent): Int =
      libc.epoll_ctl(epfd, op, fd, event)
    def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int =
      libc.epoll_wait(epfd, events, maxevents, timeout)
    def errno(): Int = LastError.getLastError(globalRuntime)
    def strerror(errnum: Int): String = libc.strerror(errnum)
    def close(fd: Int): Int = libc.close(fd)
  }
}
