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

import jnr.ffi.{LibraryLoader, Platform, Pointer, Runtime, LastError}

sealed trait libcFFIInterface {
  def epoll_create1(flags: Int): Int

  def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Pointer): Int

  def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int

  def errno(): Int

  def strerror(errnum: Int): String

  def close(fd: Int): Int
}

private[epoll] object libc {

  object jnr extends libcFFIInterface {
    private trait jnrLibcFFIInterface {
      def epoll_create1(flags: Int): Int
      def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Pointer): Int
      def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int
      def errno(): Int
      def strerror(errnum: Int): String
      def close(fd: Int): Int
    }

    private lazy val libc = LibraryLoader
      .create(classOf[jnrLibcFFIInterface])
      .load(Platform.getNativePlatform().getStandardCLibraryName())

    private lazy val runtime = Runtime.getSystemRuntime

    def epoll_create1(flags: Int): Int = libc.epoll_create1(flags)
    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Pointer): Int =
      libc.epoll_ctl(epfd, op, fd, event)
    def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int =
      libc.epoll_wait(epfd, events, maxevents, timeout)
    def errno(): Int = LastError.getLastError(runtime)
    def strerror(errnum: Int): String = libc.strerror(errnum)
    def close(fd: Int): Int = libc.close(fd)
  }
}
