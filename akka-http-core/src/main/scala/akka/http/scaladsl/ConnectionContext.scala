/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.stream.{ ConnectionException, TLSClientAuth }
import akka.stream.TLSProtocol._
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.collection.JavaConverters._
import java.util.{ Collections, Optional, Collection => JCollection }

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import com.typesafe.sslconfig.akka.util.AkkaLoggerFactory
import com.typesafe.sslconfig.ssl.DefaultHostnameVerifier
import javax.net.ssl._

import scala.collection.immutable
import scala.compat.java8.OptionConverters._
import scala.util.{ Failure, Success, Try }

trait ConnectionContext extends akka.http.javadsl.ConnectionContext {
  @deprecated("Internal method, left for binary compatibility", since = "10.2.0")
  protected[http] def defaultPort: Int
}

object ConnectionContext {
  // ConnectionContext
  //#https-context-creation
  def https(createSSLEngine: Option[(String, Int)] => () => SSLEngine) =
    new HttpsConnectionContext(Right(o => Engine(createSSLEngine(o))))

  def httpsServer(sslContext: SSLContext) =
    new HttpsConnectionContext(Right({
      case None => Engine(() => {
        val engine = sslContext.createSSLEngine()
        engine.setUseClientMode(false)
        engine
      })
      case Some((host, port)) => Engine(() => {
        val engine = sslContext.createSSLEngine(host, port)
        engine.setUseClientMode(false)
        engine
      })
    }))

  def httpsClient(context: SSLContext)(implicit system: ActorSystem) = {
    val verifier = new DefaultHostnameVerifier(new AkkaLoggerFactory(system))

    new HttpsConnectionContext(Right {
      case None =>
        Engine(() => {
          val engine = context.createSSLEngine()
          engine.getSSLParameters.setEndpointIdentificationAlgorithm("https")
          engine.setUseClientMode(true)
          engine
        })
      case Some((host, port)) =>
        Engine(
          () => {
            val engine = context.createSSLEngine(host, port)
            engine.getSSLParameters.setEndpointIdentificationAlgorithm("https")
            engine.getSSLParameters.setServerNames(Collections.singletonList(new SNIHostName(host)))
            engine.setUseClientMode(true)
            engine
          },
          sslSession => {
            if (verifier.verify(host, sslSession)) Success(())
            else Failure(new ConnectionException(s"Hostname verification failed! Expected session to be for $host"))
          })
    })
  }
  //#https-context-creation

  @deprecated("we discourage AkkaSSLConfig now", "10.2.0")
  def https(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig]         = None,
    enabledCipherSuites: Option[immutable.Seq[String]] = None,
    enabledProtocols:    Option[immutable.Seq[String]] = None,
    clientAuth:          Option[TLSClientAuth]         = None,
    sslParameters:       Option[SSLParameters]         = None) =
    new HttpsConnectionContext(Left(sslContext), sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  def noEncryption() = HttpConnectionContext
}

private[http] case class Engine(create: () => SSLEngine, validate: SSLSession => Try[Unit] = _ => Success(()))

/**
 *  Context with all information needed to set up a HTTPS connection
 *
 * This constructor is INTERNAL API, use ConnectionContext.https instead
 */
@InternalApi
final class HttpsConnectionContext(
  val sslContextData:      Either[SSLContext, Option[(String, Int)] => Engine],
  val sslConfig:           Option[AkkaSSLConfig]                               = None,
  val enabledCipherSuites: Option[immutable.Seq[String]]                       = None,
  val enabledProtocols:    Option[immutable.Seq[String]]                       = None,
  val clientAuth:          Option[TLSClientAuth]                               = None,
  val sslParameters:       Option[SSLParameters]                               = None)
  extends akka.http.javadsl.HttpsConnectionContext with ConnectionContext {
  protected[http] override final def defaultPort: Int = 443

  @deprecated("not always available", "10.2.0")
  def sslContext: SSLContext = sslContextData.left.getOrElse(null)

  def firstSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  @deprecated("not always available", "10.2.0")
  override def getSslContext = sslContext
  override def getEnabledCipherSuites: Optional[JCollection[String]] = enabledCipherSuites.map(_.asJavaCollection).asJava
  override def getEnabledProtocols: Optional[JCollection[String]] = enabledProtocols.map(_.asJavaCollection).asJava
  override def getClientAuth: Optional[TLSClientAuth] = clientAuth.asJava
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
