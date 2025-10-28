/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.testkit

import akka.http.javadsl.model.headers.Host

final case class DefaultHostInfo(private val host: Host, private val securedConnection: Boolean) {

  def getHost(): Host = host

  def isSecuredConnection(): Boolean = securedConnection

}
