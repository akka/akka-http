/*
 * Copyright (C) 2020-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model

import javax.net.ssl.SSLSession

import akka.http.scaladsl.{ model => sm }

trait SslSessionInfo {

  /**
   * Java API
   */
  def getSession: SSLSession
}
object SslSessionInfo {
  def create(session: SSLSession): SslSessionInfo = sm.SslSessionInfo(session)
}
