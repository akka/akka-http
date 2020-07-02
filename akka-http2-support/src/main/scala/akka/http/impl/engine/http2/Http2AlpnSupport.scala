/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.function.BiFunction
import java.{ util => ju }

import javax.net.ssl.SSLEngine
import akka.annotation.InternalApi
import akka.http.impl.util.JavaVersion
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.impl.io.TlsUtils

import scala.util.Try

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
    else throw new RuntimeException(s"Need to run on a JVM >= 8u252 for ALPN support needed for HTTP/2. Running on ${sys.props("java.version")}")

  def isAlpnSupportedByJDK: Boolean =
    // ALPN is supported starting with JDK 9
    JavaVersion.majorVersion >= 9 ||
      (classOf[SSLEngine].getMethods.exists(_.getName == "setHandshakeApplicationProtocolSelector")
        && {
          // This method only exists in the jetty-alpn provided implementation. If it exists an old version of the jetty-alpn-agent is active which is not supported
          // on JDK>= 8u252. When running on such a JVM, you can either just remove the agent or (if you want to support older JVMs with the same command line),
          // use jetty-alpn-agent >= 2.0.10
          val jettyAlpnClassesAvailable = Try(Class.forName("sun.security.ssl.ALPNExtension")).toOption.exists(_.getDeclaredMethods.exists(_.getName == "init"))
          if (jettyAlpnClassesAvailable) throw new RuntimeException("On JDK >= 8u252 you need to either remove jetty-alpn-agent or use version 2.0.10 (which is a noop)")
          else true
        })

  private type SSLEngineWithALPNSupport = {
    def setHandshakeApplicationProtocolSelector(selector: BiFunction[SSLEngine, ju.List[String], String]): Unit
  }
  def jdkAlpnSupport(engine: SSLEngine, setChosenProtocol: String => Unit): SSLEngine = {
    engine.asInstanceOf[SSLEngineWithALPNSupport].setHandshakeApplicationProtocolSelector { (engine: SSLEngine, protocols: ju.List[String]) =>
      val chosen = chooseProtocol(protocols)
      chosen.foreach(setChosenProtocol)

      //returning null here means aborting the handshake
      //see https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngine.html#setHandshakeApplicationProtocolSelector(java.util.function.BiFunction)
      chosen.orNull
    }

    engine
  }

  private def chooseProtocol(protocols: ju.List[String]): Option[String] =
    if (protocols.contains(H2)) Some(H2)
    else if (protocols.contains(HTTP11)) Some(HTTP11)
    else None

  def applySessionParameters(engine: SSLEngine, sessionParameters: NegotiateNewSession): Unit = TlsUtils.applySessionParameters(engine, sessionParameters)

  private type SSLParametersWithALPNSupport = {
    def setApplicationProtocols(protocols: Array[String]): Unit
  }
  def clientSetApplicationProtocols(engine: SSLEngine, protocols: Array[String]): Unit = {
    val params = engine.getSSLParameters
    params.asInstanceOf[SSLParametersWithALPNSupport].setApplicationProtocols(Array("h2"))
    engine.setSSLParameters(params)
  }

}
