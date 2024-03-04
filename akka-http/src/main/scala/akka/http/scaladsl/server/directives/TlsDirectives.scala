/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
 package akka.http.scaladsl.server.directives

 import akka.http.scaladsl.model.headers.`Tls-Session-Info`
 import akka.http.scaladsl.server.ClientCertMissingRejection
 import akka.http.scaladsl.server.Directive0
 import akka.http.scaladsl.server.Directive1
 import akka.http.scaladsl.server.ValidationRejection

 import java.security.cert.X509Certificate
 import javax.net.ssl.SSLSession

 /**
  * @groupname tls TLS directives
  */
trait TlsDirectives {
   import BasicDirectives._
   import HeaderDirectives._
   import RouteDirectives._

   /**
    * Using this directive requires tls-session info parsing to be enabled:
    * `akka.httpserver.parsing.tls-session-info-header = on`
    */
   def extractSslSession: Directive1[SSLSession] =
     optionalHeaderValueByType(`Tls-Session-Info`).flatMap {
       case Some(sessionInfo) => provide(sessionInfo.session)
       case None =>
          // FIXME this could be a misconfiguration, should we try to detect and log?
           reject(ClientCertMissingRejection())
     }

   def extractClientCertificate: Directive1[X509Certificate] =
     extractSslSession.flatMap { session =>
       session.getPeerCertificates.head match {
         case cert: X509Certificate => provide(cert)
         case other => throw new RuntimeException(s"Unexpected type of peer certificate: [${other.getType}]")
       }
     }
   def requireClientCertificateCN(name: String): Directive0 =
     extractClientCertificate.flatMap { clientCert =>
       reject(ValidationRejection(
         s"""
            |clientCert.getSubjectX500Principal.getName: ${clientCert.getSubjectX500Principal.getName}
            |clientCert.getSubjectX500Principal.getName: ${clientCert.getSubjectAlternativeNames}
            """.stripMargin))
     }

}
