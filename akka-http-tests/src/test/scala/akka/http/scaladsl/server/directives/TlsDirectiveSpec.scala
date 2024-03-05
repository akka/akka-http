/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.scaladsl.server.directives

import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.{ ClientTransport, ConnectionContext, Http, HttpsConnectionContext }
import akka.pki.pem.{ DERPrivateKeyLoader, PEMDecoder }
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

import java.io.InputStream
import java.net.InetSocketAddress
import java.security.cert.{ Certificate, CertificateFactory }
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.io.Source

class TlsDirectiveSpec extends AkkaSpec(ConfigFactory.parseString("akka.http.server.parsing.tls-session-info-header = on")) {

  // Note: this actually starts a server rather than use route tests and simulate mTLS to be sure
  //       we cover how TLS actually behaves

  private var binding: ServerBinding = _

  private val serverHost = "127.0.0.1"
  private val serverPort = 9443
  private val serverUrl = s"https://example.com:$serverPort"

  // Fake that example.com resolves to 127.0.0.1
  private val fakeServerLookup = ConnectionPoolSettings(system).withUpdatedConnectionSettings(_.withTransport(ClientTransport.withCustomResolver {
    case ("example.com", port) => Future.successful(new InetSocketAddress(serverHost, port))
  }))

  import akka.http.scaladsl.server.Directives._

  private val routes = concat(
    path("ssl-session") {
      extractSslSession { session =>
        complete(session.getCipherSuite)
      }
    },
    path("client-cert") {
      extractClientCertificate { clientCert =>
        complete(clientCert.getSubjectX500Principal.getName)
      }
    },
    path("require-cn") {
      requireClientCertificateCN("client1") {
        complete("OK")
      }
    }
  )

  override def atStartup(): Unit = {
    binding = Http(system).newServerAt(serverHost, serverPort).enableHttps(serverConnectionContext).bind(routes).futureValue
  }

  override protected def beforeTermination(): Unit = {
    if (binding != null) binding.terminate(3.seconds).futureValue
  }

  private def sendRequest(request: HttpRequest, connectionContext: HttpsConnectionContext): (HttpResponse, String) = {
    val response = Http(system).singleRequest(request, connectionContext, fakeServerLookup).futureValue
    (response, response.entity.toStrict(3.seconds).futureValue.data.utf8String)
  }

  "TLS directives" should {

    "extract SSLSession from a client with trusted cert" in {
      val (response, _) = sendRequest(HttpRequest(uri = s"$serverUrl/ssl-session"), trustedClientConnectionContext)
      response.status should ===(StatusCodes.OK)
    }

    "extract SSLSession from a client without cert" in {
      val (response, _) = sendRequest(HttpRequest(uri = s"$serverUrl/ssl-session"), clientConnectionContextWithoutCert)
      response.status should ===(StatusCodes.OK)
    }

    "extract client cert from a client with trusted cert" in {
      val (response, body) = sendRequest(HttpRequest(uri = s"$serverUrl/client-cert"), trustedClientConnectionContext)
      response.status should ===(StatusCodes.OK)
      body should ===("CN=client1,OU=Akka Team,O=Lightbend,L=Stockholm,ST=Svealand,C=SE")
    }

    "not extract client cert from a client with no cert" in {
      val (response, _) = sendRequest(HttpRequest(uri = s"$serverUrl/client-cert"), clientConnectionContextWithoutCert)
      response.status should ===(StatusCodes.Unauthorized)
    }

    "require client CN from a client with trusted cert" in {
      val (response, body) = sendRequest(HttpRequest(uri = s"$serverUrl/require-cn"), trustedClientConnectionContext)
      response.status should ===(StatusCodes.OK)
      body should ===("OK")
    }

    "require client CN from a client with no cert" in {
      val (response, _) = sendRequest(HttpRequest(uri = s"$serverUrl/require-cn"), clientConnectionContextWithoutCert)
      response.status should ===(StatusCodes.Unauthorized)
    }

  }

  def trustedClientConnectionContext: HttpsConnectionContext = {
    val clientPrivateKey =
      DERPrivateKeyLoader.load(PEMDecoder.decode(Source.fromResource("certs/client1.key").mkString))
    val certFactory = CertificateFactory.getInstance("X.509")

    // keyStore is for the client cert and private key
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null)
    keyStore.setKeyEntry(
      "private",
      clientPrivateKey,
      // No password for our private client key
      new Array[Char](0),
      Array[Certificate](certFactory.generateCertificate(getClass.getResourceAsStream("/certs/client1.crt"))))
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, null)
    val keyManagers = keyManagerFactory.getKeyManagers

    // trustStore is for what server certs the client trust (any cert signed by the fake CA)
    val trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null)
    trustStore.setEntry(
      "exampleCA",
      new KeyStore.TrustedCertificateEntry(
        certFactory.generateCertificate(getClass.getResourceAsStream("/certs/exampleca.crt"))),
      null)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    val trustManagers = tmf.getTrustManagers

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagers, trustManagers, new SecureRandom())

    ConnectionContext.httpsClient(context)
  }

  def clientConnectionContextWithoutCert: HttpsConnectionContext = {
    val certFactory = CertificateFactory.getInstance("X.509")

    // trustStore is for what server certs the client trust (any cert signed by the fake CA)
    val trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null)
    trustStore.setEntry(
      "exampleCA",
      new KeyStore.TrustedCertificateEntry(
        certFactory.generateCertificate(getClass.getResourceAsStream("/certs/exampleca.crt"))),
      null)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    val trustManagers = tmf.getTrustManagers

    val context = SSLContext.getInstance("TLS")
    context.init(null, trustManagers, new SecureRandom())

    ConnectionContext.httpsClient(context)
  }

  def serverConnectionContext: HttpsConnectionContext = {
    val clientPrivateKey =
      DERPrivateKeyLoader.load(PEMDecoder.decode(Source.fromResource("certs/example.com.key").mkString))
    val certFactory = CertificateFactory.getInstance("X.509")

    // keyStore is for the server cert and private key
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null)
    keyStore.setKeyEntry(
      "private",
      clientPrivateKey,
      // No password for our private client key
      new Array[Char](0),
      Array[Certificate](certFactory.generateCertificate(getClass.getResourceAsStream("/certs/example.com.crt"))))
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, null)
    val keyManagers = keyManagerFactory.getKeyManagers

    // trustStore is for what server certs the client trust, se use the exampletrust.jks which contains
    // the fake CA, so any cert signed by the CA should be trusted by the server
    val trustStore: KeyStore = KeyStore.getInstance("JKS")
    val keystoreStream: InputStream = getClass.getClassLoader.getResourceAsStream("certs/exampletrust.jks")
    if (keystoreStream == null) fail("exampletrust.jks could not be found")
    trustStore.load(keystoreStream, "changeit".toCharArray)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    val trustManagers = tmf.getTrustManagers

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagers, trustManagers, new SecureRandom)
    ConnectionContext.httpsServer { () =>
      val engine = sslContext.createSSLEngine()

      engine.setUseClientMode(false)

      // client cert data only available if we want or need it, use want here so we can test
      // both scenarios
      engine.setWantClientAuth(true)

      engine
    }
  }

}
