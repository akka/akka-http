/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl

import akka.annotation.InternalApi
import akka.http.scaladsl.server.TlsClientIdentityRejection
import akka.http.scaladsl.server.directives.BasicDirectives.pass
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.directives.TlsDirectives
import akka.util.OptionVal

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ClientCertificateUtils {

  private val sanDnsNameType = 2
  private val sanIpAddressType = 7

  def extractCN(certificate: X509Certificate): Option[String] = {
    // We can't use the parsed set of attribute types and values because that is internal in X500Name
    extractCN(certificate.getSubjectX500Principal.getName())
  }

  // Full RFC https://www.rfc-editor.org/rfc/rfc2253
  // Note: This is not a general purpose RFC 2553 parser but specifically for what the JDK X500Name outputs
  // so no support for semicolon separator
  private[http] def extractCN(rfc2253Name: String): Option[String] = {
    // but let's take shortcuts if possible, we are only interested in CN
    val cnPos = rfc2253Name.indexOf("CN=")
    if (cnPos == -1) None
    else {
      var endOfCn = cnPos + 3
      while (endOfCn < rfc2253Name.length && (rfc2253Name.charAt(endOfCn) != ',' || rfc2253Name.charAt(endOfCn - 1) == '\\')) {
        endOfCn += 1
      }
      Some(rfc2253Name.substring(cnPos + 3, endOfCn))
    }
  }

  def extractIpAndDnsSANs(certificate: X509Certificate): Seq[String] = {
    val altNames = certificate.getSubjectAlternativeNames
    if (altNames == null) Vector.empty
    else {
      val iterator = altNames.iterator()
      val altNamesBuilder = Vector.newBuilder[String]
      while (iterator.hasNext) {
        val altName = iterator.next()
        val entryType = altName.get(0)
        if (entryType == sanDnsNameType || entryType == sanIpAddressType) {
          val name = altName.get(1).asInstanceOf[String]
          altNamesBuilder += name
        }
      }

      altNamesBuilder.result()
    }
  }
}
