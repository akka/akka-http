/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
 package akka.http.scaladsl.server.directives

 import akka.http.scaladsl.model.StatusCodes
 import akka.http.scaladsl.server.RoutingSpec

 class TlsDirectiveSpec extends RoutingSpec {
   // Note that in actual server `akka.httpserver.parsing.tls-session-info-header = on`
   // must be set or SSLSession is not propagated to routes

   "TLS directives" should {

     "reject when there is no SSLSession" in {
       val route = path("cert") {
         extractSslSession { session =>
           complete(session.getCipherSuite)
         }
       }

       Get("/cert") ~!> route ~> check {
         // FIXME is this an internal server error rather?
         // FIXME What about mixed HTTP/HTTPS servers?
         status should ===(StatusCodes.Unauthorized)
       }
     }


     "reject when there is no client certificate" in {
       val route = path("cert") {
         extractClientCertificate { clientCertificate =>
           complete(clientCertificate.getSubjectX500Principal.getName)
         }
       }

       Get("/cert") ~!> route ~> check {
         status should ===(StatusCodes.Unauthorized)
       }
     }

     "extract a client certificate" in {
       val route = path("cert") {
         requireClientCertificateCN("something") {
           complete("ok")
         }
       }

       Get("/cert") ~!> route ~> check {
         status should ===(StatusCodes.OK)
       }
     }

   }


}
