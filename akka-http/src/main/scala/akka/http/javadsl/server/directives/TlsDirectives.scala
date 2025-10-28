/*
 * Copyright (C) 2024 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.server.directives

import akka.http.javadsl.server.Route

import java.util.function.{ Function => JFunction, Supplier }
import javax.net.ssl.SSLSession
import akka.http.scaladsl.server.{ Directives => D }

import java.security.cert.X509Certificate

abstract class TlsDirectives extends CorsDirectives {
  /**
   * Extract the current SSLSession.
   *
   * Note: Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   */
  def extractSslSession(inner: JFunction[SSLSession, Route]): Route = RouteAdapter {
    D.extractSslSession(session => inner.apply(session).delegate)
  }

  /**
   * Extract the client certificate, or reject the request with a [[akka.http.javadsl.server.TlsClientUnverifiedRejection]].
   *
   * Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   *
   * The [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def extractClientCertificate(inner: JFunction[X509Certificate, Route]): Route = RouteAdapter {
    D.extractClientCertificate(certificate => inner.apply(certificate).delegate)
  }

  /**
   * Require the client to be authenticated, if not reject the request with a [[akka.http.javadsl.server.TlsClientUnverifiedRejection]], also require
   * the one of the client certificate `ip` or `dns` SANs (Subject Alternative Name) or if non exists, the CN (Common Name)
   * to match the given regular expression, if not the request is rejected with a [[akka.http.javadsl.server.TlsClientIdentityRejection]]
   *
   * Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   *
   * The [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def requireClientCertificateIdentity(cnRegex: String, inner: Supplier[Route]): Route = RouteAdapter {
    D.requireClientCertificateIdentity(cnRegex.r)(inner.get().delegate)
  }
}
