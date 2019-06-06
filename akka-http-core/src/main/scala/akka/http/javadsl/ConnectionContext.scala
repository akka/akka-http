/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.{ Optional, Collection => JCollection }

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import akka.japi.Util
import akka.stream.TLSClientAuth
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import javax.net.ssl.{ SSLContext, SSLParameters }

import scala.compat.java8.OptionConverters

object ConnectionContext {
  //#https-context-creation
  // ConnectionContext
  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.https(sslContext)
  //#https-context-creation

  /** Used to serve HTTPS traffic. */
  def https(
    sslContext:          SSLContext,
    sslConfig:           Optional[AkkaSSLConfig],
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.https(
      sslContext,
      OptionConverters.toScala(sslConfig),
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters),
      scaladsl.UseHttp2.Negotiated)

  /** Used to serve HTTPS traffic. */
  // for binary-compatibility, since 2.4.7
  def https(
    sslContext:          SSLContext,
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(
      sslContext,
      None,
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters))

  /** Used to serve HTTP traffic. */
  def noEncryption(): HttpConnectionContext =
    scaladsl.ConnectionContext.noEncryption()
}

@DoNotInherit
abstract class ConnectionContext {
  def isSecure: Boolean
  def sslConfig: Option[AkkaSSLConfig]
  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def http2: UseHttp2 = null

  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def withHttp2(newValue: UseHttp2): ConnectionContext

  @deprecated("'default-http-port' and 'default-https-port' configuration properties are used instead", since = "10.0.11")
  def getDefaultPort: Int
}

@DoNotInherit
abstract class HttpConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = false
  override final def getDefaultPort = 80
  override def sslConfig: Option[AkkaSSLConfig] = None

  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  override def withHttp2(newValue: UseHttp2): HttpConnectionContext
}

@DoNotInherit
abstract class HttpsConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = true
  override final def getDefaultPort = 443
  @deprecated("this field is planned to disappear in 10.2.0", "10.1.9")
  override val http2: UseHttp2 = null

  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  override def withHttp2(newValue: UseHttp2): HttpsConnectionContext

  /** Java API */
  def getEnabledCipherSuites: Optional[JCollection[String]]
  /** Java API */
  def getEnabledProtocols: Optional[JCollection[String]]
  /** Java API */
  def getClientAuth: Optional[TLSClientAuth]

  /** Java API */
  def getSslContext: SSLContext
  /** Java API */
  def getSslParameters: Optional[SSLParameters]
}
