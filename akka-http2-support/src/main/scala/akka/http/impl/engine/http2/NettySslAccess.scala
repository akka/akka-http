/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package io.netty.handler.ssl

import javax.net.ssl.{ SSLEngine, SSLSession }

import scala.util.Try

object NettySslAccess {
  def getChosenProtocol(session: SSLSession): String = {
    println(session, session.isInstanceOf[ApplicationProtocolAccessor])
    Try(println(session.asInstanceOf[ApplicationProtocolAccessor].getApplicationProtocol))
    session match {
      case acc: ApplicationProtocolAccessor if acc.getApplicationProtocol ne null ⇒ acc.getApplicationProtocol
      case _ ⇒ "h1"
    }
  }
}
