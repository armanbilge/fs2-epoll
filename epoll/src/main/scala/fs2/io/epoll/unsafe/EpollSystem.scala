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

import cats.effect.unsafe.PollingSystem

object EpollSystem extends PollingSystem {
  import epoll._
  import libc._

  def close(): Unit = ???

  def makeApi(register: (Poller => Unit) => Unit): Api = ???

  def makePoller(): Poller = ???

  def closePoller(poller: Poller): Unit = ???

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = ???

  def needsPoll(poller: Poller): Boolean = ???

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ???

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
