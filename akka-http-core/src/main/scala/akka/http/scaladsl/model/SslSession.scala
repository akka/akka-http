/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.javadsl.{ model => jm }
import akka.stream.scaladsl.ScalaSessionAPI
import javax.net.ssl.SSLSession

class SslSession(val session: SSLSession) extends jm.SslSession with ScalaSessionAPI {

  /**
   * Java API
   */
  override def getSession: SSLSession = session

  override def equals(other: Any): Boolean = other match {
    case SslSession(`session`) => true
    case _                     => false
  }
  override def hashCode(): Int = session.hashCode()
}

object SslSession {
  def apply(session: SSLSession): SslSession = new SslSession(session)
  def unapply(sslSession: SslSession): Option[SSLSession] = Some(sslSession.session)
}
