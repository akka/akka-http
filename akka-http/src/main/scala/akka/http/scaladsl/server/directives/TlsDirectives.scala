/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.impl.ClientCertificateUtils
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.TlsClientIdentityRejection
import akka.http.scaladsl.server.TlsClientUnverifiedRejection

import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import scala.util.matching.Regex

/**
 *
 * @groupname tls TLS directives
 */
trait TlsDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._

  /**
   * Extract the current SSLSession.
   *
   * Note: Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   */
  def extractSslSession: Directive1[SSLSession] =
    optionalHeaderValueByType(`Tls-Session-Info`).flatMap {
      case Some(sessionInfo) => provide(sessionInfo.session)
      case None =>
        throw new RuntimeException("Tried to extract SSLSession but there was no synthetic header, " +
          "make sure to enable 'akka.http.server.parsing.tls-session-info-header = on' in the server configuration")
    }

  /**
   * Extract the client certificate, or reject the request with a [[TlsClientUnverifiedRejection]].
   *
   * Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   *
   * The [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def extractClientCertificate: Directive1[X509Certificate] =
    extractSslSession.flatMap { session =>
      try {
        session.getPeerCertificates.head match {
          case cert: X509Certificate => provide(cert)
          case other                 => throw new RuntimeException(s"Unexpected type of peer certificate: [${other.getType}]")
        }
      } catch {
        case _: SSLPeerUnverifiedException =>
          reject(TlsClientUnverifiedRejection("No client certificate found or client certificate not trusted"))
      }
    }

  /**
   * Require the client to be authenticated, if not reject the request with a [[TlsClientUnverifiedRejection]], also require
   * the one of the client certificate `ip` or `dns` SANs (Subject Alternative Name) or if non exists, the CN (Common Name)
   * to match the given regular expression, if not the request is rejected with a [[TlsClientIdentityRejection]]
   *
   * Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
   *
   * The [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def requireClientCertificateIdentity(cnRegex: Regex): Directive0 =
    extractClientCertificate.flatMap { clientCert =>
      val altNames = ClientCertificateUtils.extractIpAndDnsSANs(clientCert)
      if (altNames.exists(cnRegex.matches)) pass
      else {
        // CN is deprecated but still widely used
        val cn = ClientCertificateUtils.extractCN(clientCert)
        if (cn.exists(cnRegex.matches)) pass
        else reject(TlsClientIdentityRejection("Client certificate does not fulfill identity requirement", cnRegex.regex, cn, altNames))
      }
    }
}

object TlsDirectives extends TlsDirectives
