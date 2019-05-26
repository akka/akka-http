/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.function.BiFunction
import java.{ util => ju }

import javax.net.ssl.{ SSLEngine, SSLParameters }
import akka.annotation.InternalApi
import akka.http.impl.util.JavaVersion
import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol.NegotiateNewSession
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
        setChosenProtocol(chosen)
        chosen
      }
    })

    engine
  }

  def jettyAlpnSupport(engine: SSLEngine, setChosenProtocol: String => Unit): SSLEngine = {
    ALPN.put(engine, new ServerProvider {
      override def select(protocols: ju.List[String]): String =
        choose {
          chooseProtocol(protocols)
        }

      override def unsupported(): Unit =
        choose("h1")

      def choose(protocol: String): String = try {
        setChosenProtocol(protocol)
        protocol
      } finally ALPN.remove(engine)
    })
    engine
  }

  def chooseProtocol(protocols: ju.List[String]): String =
    if (protocols.contains("h2")) "h2"
    else "h1"

  // copy from akka.stream.impl.io.TlsUtils which is inaccessible because of private[stream]
  // FIXME: replace by direct access as should be provided by akka/akka#22116
  def applySessionParameters(engine: SSLEngine, sessionParameters: NegotiateNewSession): Unit = {
    sessionParameters.enabledCipherSuites foreach (cs => engine.setEnabledCipherSuites(cs.toArray))
    sessionParameters.enabledProtocols foreach (p => engine.setEnabledProtocols(p.toArray))
    sessionParameters.clientAuth match {
      case Some(TLSClientAuth.None) => engine.setNeedClientAuth(false)
      case Some(TLSClientAuth.Want) => engine.setWantClientAuth(true)
      case Some(TLSClientAuth.Need) => engine.setNeedClientAuth(true)
      case _                        => // do nothing
    }

    sessionParameters.sslParameters.foreach(engine.setSSLParameters)
  }

  def cloneParameters(old: SSLParameters): SSLParameters = {
    val newParameters = new SSLParameters()
    newParameters.setAlgorithmConstraints(old.getAlgorithmConstraints)
    newParameters.setCipherSuites(old.getCipherSuites)
    newParameters.setEndpointIdentificationAlgorithm(old.getEndpointIdentificationAlgorithm)
    newParameters.setNeedClientAuth(old.getNeedClientAuth)
    newParameters.setProtocols(old.getProtocols)
    newParameters.setServerNames(old.getServerNames)
    newParameters.setSNIMatchers(old.getSNIMatchers)
    newParameters.setUseCipherSuitesOrder(old.getUseCipherSuitesOrder)
    newParameters.setWantClientAuth(old.getWantClientAuth)
    newParameters
  }

  def cleanupForServer(engine: SSLEngine): Unit =
    if (!isAlpnSupportedByJDK) ALPN.remove(engine)

}
