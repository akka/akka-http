/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.function.BiFunction
import java.{ util => ju }

import javax.net.ssl.{ SSLEngine, SSLException }
import akka.annotation.InternalApi
import akka.http.impl.util.JavaVersion
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.impl.io.TlsUtils
import org.eclipse.jetty.alpn.ALPN
import org.eclipse.jetty.alpn.ALPN.ServerProvider

import scala.language.reflectiveCalls

/**
 * INTERNAL API
 *
 * Will add support to an engine either using jetty alpn or using netty APIs (later).
 */
@InternalApi
private[http] object Http2AlpnSupport {
  //ALPN Protocol IDs https://www.iana.org/assignments/tls-extensiontype-values/tls-extensiontype-values.xhtml#alpn-protocol-ids
  val H2 = "h2"
  val HTTP11 = "http/1.1"
  /**
   * Enables server-side Http/2 ALPN support for the given engine.
   */
  def enableForServer(engine: SSLEngine, setChosenProtocol: String => Unit): SSLEngine =
    if (isAlpnSupportedByJDK) jdkAlpnSupport(engine, setChosenProtocol)
    else jettyAlpnSupport(engine, setChosenProtocol)

  def isAlpnSupportedByJDK: Boolean =
    // ALPN is supported starting with JDK 9
    JavaVersion.majorVersion >= 9

  private type JDK9SSLEngine = {
    def setHandshakeApplicationProtocolSelector(selector: BiFunction[SSLEngine, ju.List[String], String]): Unit
  }
  def jdkAlpnSupport(engine: SSLEngine, setChosenProtocol: String => Unit): SSLEngine = {
    engine.asInstanceOf[JDK9SSLEngine].setHandshakeApplicationProtocolSelector(new BiFunction[SSLEngine, ju.List[String], String] {
      // explicit style needed here as automatic SAM-support doesn't seem to work out with Scala 2.11
      override def apply(engine: SSLEngine, protocols: ju.List[String]): String = {
        val chosen = chooseProtocol(protocols)
        chosen.foreach(setChosenProtocol)

        //returning null here means aborting the handshake
        //see https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngine.html#setHandshakeApplicationProtocolSelector(java.util.function.BiFunction)
        chosen.orNull
      }
    })

    engine
  }

  def jettyAlpnSupport(engine: SSLEngine, setChosenProtocol: String => Unit): SSLEngine = {
    ALPN.put(engine, new ServerProvider {
      override def select(protocols: ju.List[String]): String =
        choose {
          //throwing an exception means aborting the handshake
          //see http://git.eclipse.org/c/jetty/org.eclipse.jetty.alpn.git/tree/src/main/java/org/eclipse/jetty/alpn/ALPN.java#n236
          chooseProtocol(protocols).getOrElse(throw new SSLException("No protocols"))
        }

      override def unsupported(): Unit =
        choose(HTTP11)

      def choose(protocol: String): String = try {
        setChosenProtocol(protocol)
        protocol
      } finally ALPN.remove(engine)
    })
    engine
  }

  private def chooseProtocol(protocols: ju.List[String]): Option[String] =
    if (protocols.contains(H2)) Some(H2)
    else if (protocols.contains(HTTP11)) Some(HTTP11)
    else None

  def applySessionParameters(engine: SSLEngine, sessionParameters: NegotiateNewSession): Unit = TlsUtils.applySessionParameters(engine, sessionParameters)

  def cleanupForServer(engine: SSLEngine): Unit =
    if (!isAlpnSupportedByJDK) ALPN.remove(engine)

}
