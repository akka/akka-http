/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model

import javax.net.ssl.SSLSession

import akka.http.scaladsl.{ model => sm }

trait SslSession {

  /**
   * Java API
   */
  def getSession: SSLSession
}
object SslSession {
  def create(session: SSLSession): SslSession = sm.SslSession(session)
}
