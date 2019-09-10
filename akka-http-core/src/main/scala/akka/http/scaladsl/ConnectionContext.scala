/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol._
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.collection.JavaConverters._
import java.util.{ Optional, Collection => JCollection }

import akka.http.javadsl
import akka.http.scaladsl.UseHttp2.Negotiated
import javax.net.ssl._

import scala.collection.immutable
import scala.compat.java8.OptionConverters._

trait ConnectionContext extends akka.http.javadsl.ConnectionContext {
  final def defaultPort = getDefaultPort
}

object ConnectionContext {
  // ConnectionContext
  //#https-context-creation
  def https(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig]         = None,
    enabledCipherSuites: Option[immutable.Seq[String]] = None,
    enabledProtocols:    Option[immutable.Seq[String]] = None,
    clientAuth:          Option[TLSClientAuth]         = None,
    sslParameters:       Option[SSLParameters]         = None) =
    new HttpsConnectionContext(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)
  //#https-context-creation

  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def https(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig],
    enabledCipherSuites: Option[immutable.Seq[String]],
    enabledProtocols:    Option[immutable.Seq[String]],
    clientAuth:          Option[TLSClientAuth],
    sslParameters:       Option[SSLParameters],
    http2:               UseHttp2) =
    new HttpsConnectionContext(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  // for bincompat
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  private[scaladsl] def https$default$7(): akka.http.scaladsl.UseHttp2 = UseHttp2.Negotiated

  @deprecated("for binary-compatibility", "2.4.7")
  def https(
    sslContext:          SSLContext,
    enabledCipherSuites: Option[immutable.Seq[String]],
    enabledProtocols:    Option[immutable.Seq[String]],
    clientAuth:          Option[TLSClientAuth],
    sslParameters:       Option[SSLParameters]) =
    new HttpsConnectionContext(sslContext, sslConfig = None, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters, http2 = Negotiated)

  def noEncryption() = HttpConnectionContext
}

final class HttpsConnectionContext(
  val sslContext:          SSLContext,
  val sslConfig:           Option[AkkaSSLConfig]         = None,
  val enabledCipherSuites: Option[immutable.Seq[String]] = None,
  val enabledProtocols:    Option[immutable.Seq[String]] = None,
  val clientAuth:          Option[TLSClientAuth]         = None,
  val sslParameters:       Option[SSLParameters]         = None)
  extends akka.http.javadsl.HttpsConnectionContext with ConnectionContext {

  @deprecated("This constructor is planned to disappear in 10.2.0", "10.1.9")
  def this(
    sslContext:          SSLContext,
    sslConfig:           Option[AkkaSSLConfig],
    enabledCipherSuites: Option[immutable.Seq[String]],
    enabledProtocols:    Option[immutable.Seq[String]],
    clientAuth:          Option[TLSClientAuth],
    sslParameters:       Option[SSLParameters],
    http2:               UseHttp2) =
    this(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  @deprecated("for binary-compatibility", since = "2.4.7")
  def this(
    sslContext:          SSLContext,
    enabledCipherSuites: Option[immutable.Seq[String]],
    enabledProtocols:    Option[immutable.Seq[String]],
    clientAuth:          Option[TLSClientAuth],
    sslParameters:       Option[SSLParameters]) =
    this(sslContext, sslConfig = None, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters, http2 = Negotiated)

  def firstSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  override def getSslContext = sslContext
  override def getEnabledCipherSuites: Optional[JCollection[String]] = enabledCipherSuites.map(_.asJavaCollection).asJava
  override def getEnabledProtocols: Optional[JCollection[String]] = enabledProtocols.map(_.asJavaCollection).asJava
  override def getClientAuth: Optional[TLSClientAuth] = clientAuth.asJava
  override def getSslParameters: Optional[SSLParameters] = sslParameters.asJava

  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  override def withHttp2(newValue: javadsl.UseHttp2): javadsl.HttpsConnectionContext = this
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def withHttp2(newValue: UseHttp2): javadsl.HttpsConnectionContext = this
}
object HttpsConnectionContext {
  // For binary compatibility, planned to disappear in 10.2.0
  private[http] def `<init>$default$7`(): UseHttp2 = null
}

sealed class HttpConnectionContext extends akka.http.javadsl.HttpConnectionContext with ConnectionContext {
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def this(http2: UseHttp2) = this()

  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  override def withHttp2(newValue: javadsl.UseHttp2): javadsl.HttpConnectionContext = this
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def withHttp2(newValue: UseHttp2): javadsl.HttpConnectionContext = this
}

final object HttpConnectionContext extends HttpConnectionContext {
  /** Java API */
  def getInstance() = this

  /** Java API */
  def create() = this

  /**
   * Java API
   *
   * This method is planned to disappear in 10.2.0
   */
  @Deprecated
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def create(http2: UseHttp2) = HttpConnectionContext()

  def apply() = new HttpConnectionContext()
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  def apply(http2: UseHttp2) = new HttpConnectionContext()
}
