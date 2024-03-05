/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.server.TlsClientUnverified
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.ValidationRejection

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLPeerUnverifiedException, SSLSession}
import javax.security.auth.x500.X500Principal
import scala.util.matching.Regex

/**
 * Using these directives requires tls-session info parsing to be enabled:
 * `akka.http.server.parsing.tls-session-info-header = on`
 *
 *
 * @groupname tls TLS directives
 */
trait TlsDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._

  def extractSslSession: Directive1[SSLSession] =
    optionalHeaderValueByType(`Tls-Session-Info`).flatMap {
      case Some(sessionInfo) => provide(sessionInfo.session)
      case None =>
        throw new RuntimeException("Tried to extract SSLSession but there was no synthetic header, " +
          "make sure to enable 'akka.http.server.parsing.tls-session-info-header = on' in the server configuration")
    }

  /**
   * Extract the client certificate, or reject the request with a [[TlsClientUnverified]]
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
          reject(TlsClientUnverified())
      }
    }

  /**
   * Require the client to be authenticated, if not reject the request with a [[TlsClientUnverified]], also require
   * the client certificate to match the given regular expression.
   */
  def requireClientCertificateCN(cnRegex: Regex): Directive0 =
    extractClientCertificate.flatMap { clientCert =>
      val cn = TlsDirectives.parseCN(clientCert.getSubjectX500Principal)
      reject(ValidationRejection(
        s"""
            |clientCert.getSubjectX500Principal.getName: ${}
            |clientCert.getSubjectX500Principal.getName: ${clientCert.getSubjectAlternativeNames}
            """.stripMargin))
    }
}

object TlsDirectives extends TlsDirectives {
  private def parseCN(principal: X500Principal): String = {
    
  }
}