/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpsConnectionContext
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers

import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SSLContextFactorySpec extends AkkaSpec with Matchers {
  "The SSLContextUtils" should {
    "conveniently load pem files" in {
      SSLContextFactory.createSSLContextFromPem(ConfigFactory.parseString(
        """
           my-server {
             certificate = "akka-http-tests/src/test/resources/certs/example.com.crt"
             private-key = "akka-http-tests/src/test/resources/certs/example.com.key"
             ca-certificates = ["akka-http-tests/src/test/resources/certs/exampleca.crt"]
           }
          """).getConfig("my-server"))

      SSLContextFactory.createSSLContextFromPem(
        Path.of("akka-http-tests/src/test/resources/certs/example.com.crt"),
        Path.of("akka-http-tests/src/test/resources/certs/example.com.key"),
        Seq(Path.of("akka-http-tests/src/test/resources/certs/exampleca.crt"))
      )
    }

    "create a refreshing context provider" in {
      val probe = TestProbe()
      val provider = SSLContextFactory.refreshingSSLEngineProvider(10.millis) { () =>
        probe.ref ! "Constructed"
        SSLContextFactory.createSSLContextFromPem(
          Path.of("akka-http-tests/src/test/resources/certs/example.com.crt"),
          Path.of("akka-http-tests/src/test/resources/certs/example.com.key"),
          Seq(Path.of("akka-http-tests/src/test/resources/certs/exampleca.crt"))
        )
      }
      val context1 = provider()
      probe.expectMsg("Constructed")
      Thread.sleep(20)
      val context2 = provider()
      probe.expectMsg("Constructed")
      context1 shouldNot be theSameInstanceAs (context2)
    }

    "Work for usage when running HTTPS server" in {
      val https = ConnectionContext.httpsServer(SSLContextFactory.refreshingSSLEngineProvider(50.millis) { () =>
        SSLContextFactory.createSSLContextFromPem(
          Path.of("akka-http-tests/src/test/resources/certs/example.com.crt"),
          Path.of("akka-http-tests/src/test/resources/certs/example.com.key"),
          Seq(Path.of("akka-http-tests/src/test/resources/certs/exampleca.crt"))
        )
      })
      import akka.http.scaladsl.server.Directives._
      val binding = Http(system).newServerAt("127.0.0.1", 8243).enableHttps(https).bind(get {
        complete("OK")
      }).futureValue

      try {
        def requestAndCheckResponse(): Unit = {
          val response = Http(system).singleRequest(
            HttpRequest(uri = "https://example.com:8243/"),
            clientHttpsConnectionContext(),
            // fake what server we are talking to for the cert validation
            ConnectionPoolSettings(system).withUpdatedConnectionSettings(_.withTransport(ClientTransport.withCustomResolver {
              case ("example.com", port) => Future.successful(new InetSocketAddress("127.0.0.1", port))
            }))
          ).futureValue

          response.status should ===(StatusCodes.OK)
          response.entity.discardBytes()
        }

        requestAndCheckResponse()
        Thread.sleep(100)
        requestAndCheckResponse()
      } finally {
        binding.unbind().futureValue
      }
    }
  }

  private def clientHttpsConnectionContext(): HttpsConnectionContext = {

    val ks: KeyStore = KeyStore.getInstance("JKS")
    val keystoreStream: InputStream = getClass.getClassLoader.getResourceAsStream("certs/exampletrust.jks")
    if (keystoreStream == null) fail("exampletrust.jkd could not be found")
    ks.load(keystoreStream, "changeit".toCharArray)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(null, tmf.getTrustManagers, new SecureRandom)
    ConnectionContext.httpsClient(sslContext)
  }

}
