package akka.http.javadsl.model

import javax.net.ssl.SSLSession

trait SslSession {

  /**
   * Java API
   */
  def getSession: SSLSession
}
