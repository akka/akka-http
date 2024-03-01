/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

//#imports
import java.io.InputStream
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import akka.actor.ActorSystem
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

    system.terminate()
  }

  "require-client-auth" in {
    //#require-client-auth
    val sslContext: SSLContext = ???
    ConnectionContext.httpsServer(() => {
      val engine = sslContext.createSSLEngine()
      engine.setUseClientMode(false)

      engine.setNeedClientAuth(true)
      // or: engine.setWantClientAuth(true)

      engine
    })
    //#require-client-auth
  }

  "rotate certs" in {
    // Not actually tested for now
    assume(false)
    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    val routes: Route = ???

    //#rotate-certs
    case class CachedContext(cached: SSLContext, expires: Deadline)
    class RefreshableSSLContextReader(certPath: Path, caCertPaths: Seq[Path], keyPath: Path, refreshPeriod: FiniteDuration) {

      private val rng = new SecureRandom()

      // invoked from different threads so important that this cache is thread safe
      private val contextRef = new AtomicReference[Option[CachedContext]](None)

      def getContext: SSLContext =
        contextRef.get() match {
          case Some(CachedContext(_, expired)) if expired.isOverdue() =>
            val context = constructContext()
            contextRef.set(Some(CachedContext(context, refreshPeriod.fromNow)))
            context
          case Some(CachedContext(cached, _)) => cached
          case None =>
            val context = constructContext()
            contextRef.set(Some(CachedContext(context, refreshPeriod.fromNow)))
            context
        }

      private def constructContext(): SSLContext =
        try {
          val certChain = readCerts(certPath)
          val caCertChain = caCertPaths.flatMap(readCerts)

          val keyStore = KeyStore.getInstance("JKS")
          keyStore.load(null)
          // Load the private key
          val privateKey =
            DERPrivateKeyLoader.load(PEMDecoder.decode(Files.readString(keyPath)))

          (certChain ++ caCertChain).zipWithIndex.foreach {
            case (cert, idx) =>
              keyStore.setCertificateEntry(s"cert-$idx", cert)
          }
          keyStore.setKeyEntry(
            "private-key",
            privateKey,
            "changeit".toCharArray,
            (certChain ++ caCertChain).toArray
          )

          val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
          kmf.init(keyStore, "changeit".toCharArray)
          val keyManagers = kmf.getKeyManagers

          val ctx = SSLContext.getInstance("TLS")
          ctx.init(keyManagers, Array(), rng)
          ctx
        } catch {
          case e: FileNotFoundException =>
            throw new RuntimeException(
              "SSL context could not be loaded because a cert or key file could not be found",
              e
            )
          case e: IOException =>
            throw new RuntimeException(
              "SSL context could not be loaded due to error reading cert or key file",
              e
            )
          case e: GeneralSecurityException =>
            throw new RuntimeException("SSL context could not be loaded", e)
          case e: IllegalArgumentException =>
            throw new RuntimeException("SSL context could not be loaded", e)
        }

      def readCerts(path: Path): Seq[X509Certificate] = {
        val certFactory = CertificateFactory.getInstance("X.509")
        val is = new BufferedInputStream(new FileInputStream(path.toFile))

        def hasAnotherCertificate(is: InputStream): Boolean = {
          // Read up to 16 characters, in practice, a maximum of two whitespace characters (CRLF) will be present
          is.mark(16)
          var char = is.read()
          while (char >= 0 && char.asInstanceOf[Char].isWhitespace)
            char = is.read()
          is.reset()
          char >= 0
        }

        try {
          var certs = Seq.empty[X509Certificate]
          while (hasAnotherCertificate(is)) {
            val cert = certFactory.generateCertificate(is).asInstanceOf[X509Certificate]
            certs :+= cert
          }
          if (certs.isEmpty)
            throw new IllegalArgumentException(s"Empty certificate file $path")
          certs
        } finally is.close()
      }
    }

    // defined outside of the factory function to be a single
    // shared instance
    val refreshableSSLContextReader = new RefreshableSSLContextReader(
      certPath = Paths.get("/some/path/server.crt"),
      caCertPaths = Seq(Paths.get("/some/path/serverCA.crt")),
      keyPath = Paths.get("/some/path/server.key"),
      refreshPeriod = 5.minutes
    )
    val https = ConnectionContext.httpsServer(() => {
      val context = refreshableSSLContextReader.getContext
      val engine = context.createSSLEngine()
      engine.setUseClientMode(false)
      engine
    })
    Http().newServerAt("127.0.0.1", 8080).enableHttps(https).bind(routes)

    //#rotate-certs
  }
}
