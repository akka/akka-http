/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.security.{ KeyStore, SecureRandom }
import java.security.cert.CertificateFactory

import akka.NotUsed
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import akka.stream.scaladsl._
import akka.http.impl.util._
import akka.http.scaladsl.{ ConnectionContext, Http }
import akka.http.scaladsl.model.{ AttributeKeys, HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.model.headers.Host
import javax.net.ssl.{ SSLContext, SSLEngine, TrustManagerFactory }
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.Future

class TlsEndpointVerificationSpec extends AkkaSpecWithMaterializer("""
    akka.http.parsing.ssl-session-attribute = on
  """) {
  /*
   * Useful when debugging against "what if we hit a real website"
   */
  val includeTestsHittingActualWebsites = false

  val timeout = Timeout(Span(3, Seconds))

  "The client implementation" should {
    "not accept certificates signed by unknown CA" in {
      val pipe = pipeline(Http().defaultClientHttpsContext, hostname = "akka.example.org") // default context doesn't include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org/")).failed, timeout) { e =>
        e shouldBe an[Exception]
      }
    }
    "accept certificates signed by known CA" in {
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "akka.example.org") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org:8080/")), timeout) { response =>
        response.status shouldEqual StatusCodes.OK
        val tlsInfo = response.attribute(AttributeKeys.sslSession).get
        tlsInfo.peerPrincipal.get.getName shouldEqual "CN=akka.example.org,O=Internet Widgits Pty Ltd,ST=Some-State,C=AU"
      }
    }
    "not accept certificates for foreign hosts" in {
      // We try to connect to 'hijack.de', but this connection is hijacked by a suspicious server
      // identifying as akka.example.org ;)
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "hijack.de") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://hijack.de/")).failed, timeout) { e =>
        e shouldBe an[Exception]
      }
    }
    "allow disabling endpoint verification" in {
      val context = {
        val certStore = KeyStore.getInstance(KeyStore.getDefaultType)
        certStore.load(null, null)
        // only do this if you want to accept a custom root CA. Understand what you are doing!
        certStore.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(getClass.getClassLoader.getResourceAsStream("keys/rootCA.crt")))

        val certManagerFactory = TrustManagerFactory.getInstance("SunX509")
        certManagerFactory.init(certStore)

        val context = SSLContext.getInstance("TLS")
        context.init(null, certManagerFactory.getTrustManagers, new SecureRandom)
        context
      }
      def createInsecureSslEngine(host: String, port: Int): SSLEngine = {
        val engine = context.createSSLEngine(host, port)
        engine.setUseClientMode(true)

        // WARNING: this creates an SSL Engine without enabling endpoint identification/verification procedures
        // Disabling host name verification is a very bad idea, please don't unless you have a very good reason to.
        // When in doubt, use the `ConnectionContext.httpsClient` that takes an `SSLContext` instead, or enable with:
        // engine.setSSLParameters({
        //  val params = engine.getSSLParameters
        //  params.setEndpointIdentificationAlgorithm("https")
        //  params
        // )

        engine
      }
      val clientConnectionContext = ConnectionContext.httpsClient(createInsecureSslEngine _)

      // We try to connect to 'hijack.de', and even though this connection is hijacked by a suspicious server
      // identifying as akka.example.org we want to connect anyway
      val pipe = pipeline(clientConnectionContext, hostname = "hijack.de")

      whenReady(pipe(HttpRequest(uri = "https://hijack.de:8080/")), timeout) { response =>
        response.status shouldEqual StatusCodes.OK
        val tlsInfo = response.attribute(AttributeKeys.sslSession).get
        tlsInfo.peerPrincipal.get.getName shouldEqual "CN=akka.example.org,O=Internet Widgits Pty Ltd,ST=Some-State,C=AU"
      }
    }

    if (includeTestsHittingActualWebsites) {
      /*
       * Requires the following DNS spoof to be running:
       * sudo /usr/local/bin/python ./dnschef.py --fakedomains www.howsmyssl.com --fakeip 54.173.126.144
       *
       * Read up about it on: https://tersesystems.com/2014/03/31/testing-hostname-verification/
       */
      "fail hostname verification on spoofed https://www.howsmyssl.com/" in {
        val req = HttpRequest(uri = "https://www.howsmyssl.com/")
        val ex = intercept[Exception] {
          Http().singleRequest(req).futureValue
        }
        // JDK built-in verification
        val expectedMsg = "No subject alternative DNS name matching www.howsmyssl.com found"

        var e: Throwable = ex
        while (e.getCause != null) e = e.getCause

        info("TLS failure cause: " + e.getMessage)
        e.getMessage should include(expectedMsg)
      }

      "pass hostname verification on https://www.playframework.com/" in {
        val req = HttpRequest(uri = "https://www.playframework.com/")
        val res = Http().singleRequest(req).futureValue
        res.status should ===(StatusCodes.OK)
      }
    }
  }

  def pipeline(clientContext: ConnectionContext, hostname: String): HttpRequest => Future[HttpResponse] = req =>
    Source.single(req).via(pipelineFlow(clientContext, hostname)).runWith(Sink.head)

  def pipelineFlow(clientContext: ConnectionContext, hostname: String): Flow[HttpRequest, HttpResponse, NotUsed] = {
    val handler: HttpRequest => HttpResponse = { req =>
      // verify Tls-Session-Info header information
      val name = req.attribute(AttributeKeys.sslSession).flatMap(_.localPrincipal).map(_.getName)
      if (name.exists(_ == "CN=akka.example.org,O=Internet Widgits Pty Ltd,ST=Some-State,C=AU")) HttpResponse()
      else HttpResponse(StatusCodes.BadRequest, entity = "Tls-Session-Info header verification failed")
    }

    val serverSideTls = Http().sslTlsServerStage(ExampleHttpContexts.exampleServerContext)
    val clientSideTls = Http().sslTlsClientStage(clientContext, hostname, 8080)

    val server =
      Http().serverLayer()
        .atop(serverSideTls)
        .reversed
        .join(Flow[HttpRequest].map(handler))

    val client =
      Http().clientLayer(Host(hostname, 8080))
        .atop(clientSideTls)

    client.join(server)
  }
}
