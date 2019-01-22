/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.Locale
import java.util.Optional

import scala.compat.java8.OptionConverters._

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.http.javadsl.model.Uri
import akka.http.scaladsl.UseHttp2.{ Negotiated, Never }

@DoNotInherit
abstract class ConnectHttp {
  def host: String
  def port: Int

  def isHttps: Boolean
  def connectionContext: Optional[HttpsConnectionContext]
  def http2: UseHttp2

  final def effectiveHttpsConnectionContext(fallbackContext: HttpsConnectionContext): HttpsConnectionContext =
    connectionContext.asScala
      .getOrElse(fallbackContext)
      .withHttp2(http2)

  final def effectiveConnectionContext(fallbackContext: ConnectionContext): ConnectionContext =
    connectionContext.asScala // Optional doesn't deal well with covariance
      .getOrElse(fallbackContext)
      .withHttp2(http2)

  override def toString = s"ConnectHttp($host,$port,$isHttps,$connectionContext,$http2)"
}

object ConnectHttp {

  // TODO may be optimised a bit to avoid parsing the Uri entirely for the known port cases

  /** Extracts HTTP or HTTPS connection data from given Uri. */
  def toHost(uriHost: Uri): ConnectHttp =
    toHost(uriHost, uriHost.port)

  /**
   * Extract HTTP or HTTPS connection data from given host.
   *
   * The host string may contain a URI or a <host>:<port> pair.
   */
  def toHost(host: String): ConnectHttp =
    toHost(createUriWithScheme("http", host))

  /**
   * Extracts HTTP or HTTPS connection data from given host and port.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   *
   * If the given port is 0, a new local port will be assigned by the operating system,
   * which can then be retrieved by the materialized [[akka.http.javadsl.Http.ServerBinding]].
   */
  def toHost(host: String, port: Int): ConnectHttp = {
    require(port >= 0, "port must be >= 0")
    toHost(createUriWithScheme("http", host), port)
  }

  /**
   * Extracts HTTP or HTTPS connection data from given host and port.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   *
   * If the given port is 0, a new local port will be assigned by the operating system,
   * which can then be retrieved by the materialized [[akka.http.javadsl.Http.ServerBinding]].
   */
  def toHost(host: String, port: Int, http2: UseHttp2): ConnectHttp = {
    require(port >= 0, "port must be >= 0")
    toHost(createUriWithScheme("http", host), port, http2)
  }

  private def toHost(uriHost: Uri, port: Int, http2: UseHttp2 = Never): ConnectHttp = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    if (s == "https") new ConnectHttpsImpl(uriHost.host.address, effectivePort(s, port), context = Optional.empty(), http2)
    else new ConnectHttpImpl(uriHost.host.address, effectivePort(s, port), http2)
  }

  /**
   * Extracts HTTPS connection data from given host and port.
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(uriHost: Uri): ConnectWithHttps =
    toHostHttps(uriHost, uriHost.port)

  /**
   * Extracts HTTPS connection data from given host and port.
   *
   * The host string may contain a URI or a <host>:<port> pair.
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String): ConnectWithHttps =
    toHostHttps(createUriWithScheme("https", host))

  /**
   * Extracts HTTPS connection data from given host and port, using the default HTTPS context.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   *
   * If the given port is 0, a new local port will be assigned by the operating system,
   * which can then be retrieved by the materialized [[akka.http.javadsl.Http.ServerBinding]].
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String, port: Int): ConnectWithHttps = {
    require(port >= 0, "port must be >= 0")
    toHostHttps(createUriWithScheme("https", host), port)
  }

  /**
   * Extracts HTTPS connection data from given host and port, using the default HTTPS context.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   *
   * If the given port is 0, a new local port will be assigned by the operating system,
   * which can then be retrieved by the materialized [[akka.http.javadsl.Http.ServerBinding]].
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String, port: Int, http2: UseHttp2): ConnectWithHttps = {
    require(port >= 0, "port must be >= 0")
    toHostHttps(createUriWithScheme("https", host), port, http2)
  }

  private def toHostHttps(uriHost: Uri, port: Int, http2: UseHttp2 = Negotiated): ConnectWithHttps = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    require(s == "" || s == "https", "toHostHttps used with non https scheme! Was: " + uriHost)
    new ConnectHttpsImpl(uriHost.host.address, effectivePort("https", port), context = Optional.empty(), http2)
  }

  private def createUriWithScheme(defaultScheme: String, host: String) = {
    if (host.startsWith("http://") || host.startsWith("https://")) Uri.create(host)
    else Uri.create(s"$defaultScheme://$host")
  }

  private def effectivePort(scheme: String, port: Int): Int = {
    val s = scheme.toLowerCase(Locale.ROOT)
    if (port >= 0) port
    else if (s == "https" || s == "wss") 443
    else if (s == "http" || s == "ws") 80
    else throw new IllegalArgumentException("Scheme is not http/https/ws/wss and no port given!")
  }

}

@DoNotInherit
abstract class ConnectWithHttps extends ConnectHttp {
  def withCustomHttpsContext(context: HttpsConnectionContext): ConnectWithHttps
  def withDefaultHttpsContext(): ConnectWithHttps
}

/** INTERNAL API */
@InternalApi
final class ConnectHttpImpl(val host: String, val port: Int, val http2: UseHttp2) extends ConnectHttp {
  def isHttps: Boolean = false

  def connectionContext: Optional[HttpsConnectionContext] = Optional.empty()
}

/** INTERNAL API */
@InternalApi
final class ConnectHttpsImpl(val host: String, val port: Int, val context: Optional[HttpsConnectionContext] = Optional.empty(), val http2: UseHttp2)
  extends ConnectWithHttps {

  override def isHttps: Boolean = true

  override def withCustomHttpsContext(context: HttpsConnectionContext): ConnectWithHttps =
    new ConnectHttpsImpl(host, port, Optional.of(context), http2)

  override def withDefaultHttpsContext(): ConnectWithHttps =
    new ConnectHttpsImpl(host, port, Optional.empty(), http2)

  override def connectionContext: Optional[HttpsConnectionContext] = context

}
