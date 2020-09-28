/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model

import javax.net.ssl.SSLSession

trait SslSession {

  /**
   * Java API
   */
  def getSession: SSLSession
}
