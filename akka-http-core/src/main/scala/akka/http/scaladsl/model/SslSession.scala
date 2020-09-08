/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.security.Principal
import java.security.cert.Certificate

import javax.net.ssl.{ SSLPeerUnverifiedException, SSLSession }

/** Value class to allow access to an SSLSession with Scala types */
class SslSession(val session: SSLSession) extends AnyVal {

  /**
   * Scala API: Extract the certificates that were actually used by this
   * engine during this session’s negotiation. The list is empty if no
   * certificates were used.
   */
  def localCertificates: List[Certificate] = Option(session.getLocalCertificates).map(_.toList).getOrElse(Nil)

  /**
   * Scala API: Extract the Principal that was actually used by this engine
   * during this session’s negotiation.
   */
  def localPrincipal: Option[Principal] = Option(session.getLocalPrincipal)

  /**
   * Scala API: Extract the certificates that were used by the peer engine
   * during this session’s negotiation. The list is empty if no certificates
   * were used.
   */
  def peerCertificates: List[Certificate] =
    try Option(session.getPeerCertificates).map(_.toList).getOrElse(Nil)
    catch {
      case _: SSLPeerUnverifiedException => Nil
    }

  /**
   * Scala API: Extract the Principal that the peer engine presented during
   * this session’s negotiation.
   */
  def peerPrincipal: Option[Principal] =
    try Option(session.getPeerPrincipal)
    catch {
      case _: SSLPeerUnverifiedException => None
    }
}
