/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.impl.Rfc2553Parser
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
   * Extract the client certificate, or reject the request with a [[TlsClientUnverifiedRejection]].
   *
   * Note that the [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
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
          reject(TlsClientUnverifiedRejection())
      }
    }

  /**
   * Require the client to be authenticated, if not reject the request with a [[TlsClientUnverifiedRejection]], also require
   * the client certificate CName to match the given regular expression or reject the request with [[TlsClientIdentityRejection]]
   *
   * Note that the [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def requireClientCertificateCN(cnRegex: Regex): Directive0 =
    extractClientCertificate.flatMap { clientCert =>
      val cn = Rfc2553Parser.extractCN(clientCert.getSubjectX500Principal)
      if (cn.exists(cnRegex.matches)) pass
      else reject(TlsClientIdentityRejection("CN does not fulfill requirement"))
    }

  /**
   * Require the client to be authenticated, if not reject the request with a [[TlsClientUnverifiedRejection]], also require
   * at least one of the client certificate SAN (Subject Alternative Names) to match the given regular expression or reject the
   * request with [[TlsClientIdentityRejection]].
   *
   * Note that the [[javax.net.ssl.SSLEngine]] for the server needs to be set up with `setWantClientAuth(true)` or `setNeedClientAuth(true)`
   * or else every request will be failed.
   */
  def requireClientCertificateSAN(cnRegex: Regex): Directive0 =
    extractClientCertificate.flatMap { clientCert =>
      val altNames = clientCert.getSubjectAlternativeNames
      if (altNames == null) reject(TlsClientIdentityRejection("Required SAN but there were none in certificate"))
      else {
        val iterator = altNames.iterator()
        var foundAltNames = List.empty[String]
        while (iterator.hasNext) {
          val altName = iterator.next()
          val entryType = altName.get(0)
          if (entryType == TlsDirectives.dnsNameType || entryType == TlsDirectives.ipAddressType) {
            val name = altName.get(1).asInstanceOf[String]
            foundAltNames = name :: foundAltNames
          }
        }

        if (foundAltNames.isEmpty) reject(TlsClientIdentityRejection("Required SAN but there were none in certificate"))
        else {
          if (foundAltNames.exists(cnRegex.matches)) pass
          else reject(TlsClientIdentityRejection("SAN does not fulfill requirement"))
        }
      }
    }
}

object TlsDirectives extends TlsDirectives {
  private val dnsNameType = 2
  private val ipAddressType = 7
}
