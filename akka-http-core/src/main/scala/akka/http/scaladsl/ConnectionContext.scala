/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.{ Collections, Optional, Collection => JCollection }

import javax.net.ssl._

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.compat.java8.OptionConverters._
import scala.util.{ Failure, Success, Try }
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.akka.util.AkkaLoggerFactory
import com.typesafe.sslconfig.ssl.DefaultHostnameVerifier
import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.stream.{ ConnectionException, TLSClientAuth }
import akka.stream.TLSProtocol._

trait ConnectionContext extends akka.http.javadsl.ConnectionContext {
  @deprecated("Internal method, left for binary compatibility", since = "10.2.0")
  protected[http] def defaultPort: Int
}

object ConnectionContext {
  //#https-context-creation
  /**
   *  Use this sslContext to create a HttpsConnectionContext for server-side use.
   */
  def httpsServer(sslContext: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    httpsServer(() => {
      val engine = sslContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine
    })

  /**
   *  If you want complete control over how to create the SSLEngine you can use this method:
   *
   *  Note that this means it is up to you to make sure features like SNI and Hostname Verification
   *  are enabled as needed.
   */
  @ApiMayChange
  def httpsServer(createSSLEngine: () => SSLEngine): HttpsConnectionContext =
    new HttpsConnectionContext(Right({
      case None =>
        Engine(createSSLEngine)
      case Some(_) =>
        throw new IllegalArgumentException("host and port supplied for connection based on server connection context")
    }))

  //#https-context-creation
  /**
   *  Use this sslContext to create a HttpsConnectionContext for client-side use.
   */
  def httpsClient(context: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    httpsClient((host, port) => () => {
      val engine = context.createSSLEngine(host, port)
      engine.setUseClientMode(true)

      engine.setSSLParameters({
        val params = engine.getSSLParameters
        params.setEndpointIdentificationAlgorithm("https")
        params
      })

      engine
    })

  /**
   *  If you want complete control over how to create the SSLEngine you can use this method:
   *
   *  Note that this means it is up to you to make sure features like SNI and Hostname Verification
   *  are enabled as needed.
   */
  @ApiMayChange
  def httpsClient(createSSLEngine: (String, Int) => () => SSLEngine): HttpsConnectionContext = // ...
    new HttpsConnectionContext(Right({
      case None =>
        throw new IllegalArgumentException("host and port missing for connection based on client connection context")
      case Some((host, port)) =>
        Engine(createSSLEngine(host, port))
    }))

  @deprecated("use httpsClient, httpsServer, or the lower-level SSLEngine-based constructor", "10.2.0")
  def https(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig]         = None,
    enabledCipherSuites: Option[immutable.Seq[String]] = None,
    enabledProtocols:    Option[immutable.Seq[String]] = None,
    clientAuth:          Option[TLSClientAuth]         = None,
    sslParameters:       Option[SSLParameters]         = None) =
    new HttpsConnectionContext(Left(DeprecatedSslContextParameters(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)), sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  def noEncryption() = HttpConnectionContext
}

private[http] case class Engine(create: () => SSLEngine, validate: SSLSession => Try[Unit] = _ => Success(()))

@deprecated("here to be able to keep supporting the old API", since = "10.2.0")
private[http] case class DeprecatedSslContextParameters(
  sslContext:          SSLContext,
  sslConfig:           Option[AkkaSSLConfig],
  enabledCipherSuites: Option[immutable.Seq[String]],
  enabledProtocols:    Option[immutable.Seq[String]],
  clientAuth:          Option[TLSClientAuth],
  sslParameters:       Option[SSLParameters]
) {
  def firstSession: NegotiateNewSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)
}

/**
 *  Context with all information needed to set up a HTTPS connection
 *
 * This constructor is INTERNAL API, use ConnectionContext.https instead
 */
@InternalApi
final class HttpsConnectionContext(
  val sslContextData:                                                                  Either[DeprecatedSslContextParameters, Option[(String, Int)] => Engine],
  @deprecated("here for binary compatibility", since = "10.2.0") val sslConfig:        Option[AkkaSSLConfig]                                                   = None,
  @deprecated("here for binary compatibility", since = "10.2.0") val enabledCipherSuites:Option[immutable.Seq[String]]                                         = None,
  @deprecated("here for binary compatibility", since = "10.2.0") val enabledProtocols: Option[immutable.Seq[String]]                                           = None,
  @deprecated("here for binary compatibility", since = "10.2.0") val clientAuth:       Option[TLSClientAuth]                                                   = None,
  @deprecated("here for binary compatibility", since = "10.2.0") val sslParameters:    Option[SSLParameters]                                                   = None)
  extends akka.http.javadsl.HttpsConnectionContext with ConnectionContext {
  protected[http] override final def defaultPort: Int = 443

  @deprecated("prefer ConnectionContext.httpsClient or ConnectionContext.httpsServer", "10.2.0")
  def this(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig],
    enabledCipherSuites: Option[immutable.Seq[String]],
    enabledProtocols:    Option[immutable.Seq[String]],
    clientAuth:          Option[TLSClientAuth],
    sslParameters:       Option[SSLParameters]
  ) = this(Left(DeprecatedSslContextParameters(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)), sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  @deprecated("not always available", "10.2.0")
  def sslContext: SSLContext = sslContextData.left.getOrElse(???).sslContext

  @deprecated("here for binary compatibility", since = "10.2.0")
  def firstSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  @deprecated("not always available", "10.2.0")
  override def getSslContext = sslContext
  @deprecated("here for binary compatibility", since = "10.2.0")
  override def getEnabledCipherSuites: Optional[JCollection[String]] = enabledCipherSuites.map(_.asJavaCollection).asJava
  @deprecated("here for binary compatibility", since = "10.2.0")
  override def getEnabledProtocols: Optional[JCollection[String]] = enabledProtocols.map(_.asJavaCollection).asJava
  @deprecated("here for binary compatibility", since = "10.2.0")
  override def getClientAuth: Optional[TLSClientAuth] = clientAuth.asJava
  @deprecated("here for binary compatibility", since = "10.2.0")
  override def getSslParameters: Optional[SSLParameters] = sslParameters.asJava
}

sealed class HttpConnectionContext extends akka.http.javadsl.HttpConnectionContext with ConnectionContext {
  protected[http] override final def defaultPort: Int = 80
}

final object HttpConnectionContext extends HttpConnectionContext {
  /** Java API */
  def getInstance() = this

  /** Java API */
  def create() = this

  def apply() = new HttpConnectionContext()
}
