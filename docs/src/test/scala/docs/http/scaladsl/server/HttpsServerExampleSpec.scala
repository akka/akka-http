/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

//#imports
import java.io.InputStream
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import akka.actor.ActorSystem
import akka.http.scaladsl.common.SSLContextFactory
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.{ ConnectionContext, Http, HttpsConnectionContext }
import akka.pki.pem.DERPrivateKeyLoader
import akka.pki.pem.PEMDecoder

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
//#imports

import docs.CompileOnlySpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

abstract class HttpsServerExampleSpec extends AnyWordSpec with Matchers
  with Directives with CompileOnlySpec {

  "low level api" in compileOnlySpec {
    //#low-level-default
    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher

    // Manual HTTPS configuration

    val password: Array[Char] = "change me".toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("server.p12")

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val https: HttpsConnectionContext = ConnectionContext.httpsServer(sslContext)
    //#low-level-default

    //#both-https-and-http
    // you can run both HTTP and HTTPS in the same application as follows:
    val commonRoutes: Route = get {
      complete("Hello world!")
    }
    Http().newServerAt("127.0.0.1", 443).enableHttps(https).bind(commonRoutes)
    Http().newServerAt("127.0.0.1", 80).bind(commonRoutes)
    //#both-https-and-http

    //#bind-low-level-context
    val routes: Route = get {
      complete("Hello world!")
    }
    Http().newServerAt("127.0.0.1", 8080).enableHttps(https).bind(routes)
    //#bind-low-level-context

    {
      //#convenience-cert-loading
      val https: HttpsConnectionContext = ConnectionContext.httpsServer(SSLContextFactory.createSSLContextFromPem(
        certificatePath = Paths.get("/some/path/server.crt"),
        privateKeyPath = Paths.get("/some/path/server.key"),
        trustedCaCertificatePaths = Seq(Paths.get("/some/path/serverCA.crt"))
      ))

      // or from a config block
      // my-server {
      //   certificate = "/some/path/server.crt"
      //   private-key = "/some/path/server.key"
      //   trusted-ca-certificates = ["/some/path/clientCA.crt"]
      //   # or to use the default trust store
      //   trusted-ca-certificates = "system"
      // }
      ConnectionContext.httpsServer(SSLContextFactory.createSSLContextFromPem(system.settings.config.getConfig("my-server")))
      //#convenience-cert-loading
      Http().newServerAt("127.0.0.1", 443).enableHttps(https).bind(commonRoutes)
    }
    system.terminate()
  }

  "require-client-auth" in {
    //#require-client-auth
    val sslContext: SSLContext = ???
    ConnectionContext.httpsServer(() => {
      val engine = sslContext.createSSLEngine()
      engine.setUseClientMode(false)

      // to require trusted certs for any client to allow them to connect
      engine.setNeedClientAuth(true)
      // or, for optional client certs:
      // engine.setWantClientAuth(true)

      engine
    })
    //#require-client-auth
  }

  "rotate certs" in {
    // Not actually tested here, see SSLContextFactorySpec for actual test coverage
    assume(false)
    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    val routes: Route = ???

    //#rotate-certs
    val https = ConnectionContext.httpsServer(
      SSLContextFactory.refreshingSSLEngineProvider(5.minutes) {
        () =>
          SSLContextFactory.createSSLContextFromPem(
            certificatePath = Paths.get("/some/path/server.crt"),
            privateKeyPath = Paths.get("/some/path/server.key"),
            trustedCaCertificatePaths = Seq(Paths.get("/some/path/serverCA.crt"))
          )
      })
    Http().newServerAt("127.0.0.1", 8080).enableHttps(https).bind(routes)
    //#rotate-certs
  }
}
