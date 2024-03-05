/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.impl

import akka.annotation.InternalApi
import akka.util.OptionVal

import javax.security.auth.x500.X500Principal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object Rfc2553Parser {
  // Full RFC https://www.rfc-editor.org/rfc/rfc2253
  // Note: This is not a general purpose RFC 2553 parser but specifically for what the JDK X500Name outputs
  // so no support for semicolon separator

  def extractCN(principal: X500Principal): Option[String] = {
    // We can't use the parsed set of attribute types and values because that is internal in X500Name
    extractCN(principal.getName())
  }

  def extractCN(rfc2253Name: String): Option[String] = {
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
}
