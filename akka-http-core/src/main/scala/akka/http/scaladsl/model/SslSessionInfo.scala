/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.javadsl.{ model => jm }
import akka.stream.scaladsl.ScalaSessionAPI
import javax.net.ssl.SSLSession

class SslSessionInfo(val session: SSLSession) extends jm.SslSessionInfo with ScalaSessionAPI {

  /**
   * Java API
   */
  override def getSession: SSLSession = session

  override def equals(other: Any): Boolean = other match {
    case SslSessionInfo(`session`) => true
    case _                         => false
  }
  override def hashCode(): Int = session.hashCode()
}

object SslSessionInfo {
  def apply(session: SSLSession): SslSessionInfo = new SslSessionInfo(session)
  def unapply(sslSession: SslSessionInfo): Option[SSLSession] = Some(sslSession.session)
}
