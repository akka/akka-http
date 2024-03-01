/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.scaladsl.common

import akka.annotation.ApiMayChange
import akka.pki.pem.DERPrivateKeyLoader
import akka.pki.pem.PEMDecoder

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration

object SSLContextUtils {

  private final val defaultSecureRandom = new SecureRandom()

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files.
   */
  @ApiMayChange
  def constructSSLContext(
    certificatePath:    Path,
    privateKeyPath:     Path,
    caCertificatePaths: Seq[Path]): SSLContext = constructSSLContext(certificatePath, privateKeyPath, caCertificatePaths, defaultSecureRandom)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files.
   *
   * @param secureRandom a secure random to use for the SSL context
   */
  @ApiMayChange
  def constructSSLContext(
    certificatePath:    Path,
    privateKeyPath:     Path,
    caCertificatePaths: Seq[Path],
    secureRandom:       SecureRandom): SSLContext = {
    try {
      val certChain = readCerts(certificatePath)
      val caCertChain = caCertificatePaths.flatMap(readCerts)

      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null)

      val privateKey =
        DERPrivateKeyLoader.load(
          PEMDecoder.decode(Files.readString(privateKeyPath))
        )

      (certChain ++ caCertChain).zipWithIndex.foreach {
        case (cert, idx) =>
          keyStore.setCertificateEntry(s"cert-$idx", cert)
      }
      val password = "internal_secret".toCharArray
      keyStore.setKeyEntry(
        "private-key",
        privateKey,
        password,
        (certChain ++ caCertChain).toArray
      )

      val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(keyStore, password)
      val keyManagers = kmf.getKeyManagers

      val ctx = SSLContext.getInstance("TLS")
      ctx.init(keyManagers, Array(), secureRandom)
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
  }

  private def readCerts(path: Path): Seq[X509Certificate] = {
    val certFactory = CertificateFactory.getInstance("X.509")
    val is = new BufferedInputStream(new FileInputStream(path.toFile))

    def hasAnotherCertificate(is: InputStream): Boolean = {
      // Read up to 16 characters, in practice, a maximum of two whitespace characters (CRLF) will be present
      is.mark(16)
      var char = is.read()
      while (char >= 0 && char.asInstanceOf[Char].isWhitespace) char = is.read()
      is.reset()
      char >= 0
    }

    try {
      var certs = Seq.empty[X509Certificate]
      while (hasAnotherCertificate(is)) {
        val cert =
          certFactory.generateCertificate(is).asInstanceOf[X509Certificate]
        certs :+= cert
      }
      if (certs.isEmpty)
        throw new IllegalArgumentException(s"Empty certificate file $path")
      certs
    } finally is.close()
  }

  private final case class CachedContext(context: SSLContext, expires: Deadline)

  private final class RefreshableSSLContextReader(refreshAfter: FiniteDuration, constructContext: () => SSLContext) extends (() => SSLContext) {

    private val contextRef = new AtomicReference[Option[CachedContext]](None)
    override def apply(): SSLContext = {
      val cached = contextRef.get()
      if (cached.isEmpty || cached.get.expires.isOverdue()) {
        val context = constructContext()
        contextRef.set(Some(CachedContext(context, refreshAfter.fromNow)))
        context
      } else {
        cached.get.context
      }
    }
  }

  /**
   * Keeps a created SSLContext around for a `refreshAfter` period, sharing it among connections, then creates a new
   * context. Useful for rotating certificates.
   *
   * @param refreshAfter Keep a created context around this long, then recreate it
   * @param construct A factory method to create the context when recreating is needed
   * @return An SSLEngine provider function to use with Akka HTTP `ConnectionContext.httpsServer()` and `ConnectionContext.httpsClient`.
   */
  @ApiMayChange
  def refreshingSSLEngineProvider(refreshAfter: FiniteDuration)(construct: () => SSLContext): () => SSLEngine = {
    val refreshingSSLContextProvider = new RefreshableSSLContextReader(refreshAfter, construct)

    { () =>
      val sslContext = refreshingSSLContextProvider()
      sslContext.createSSLEngine()
    }
  }

  /**
   * Keeps a created SSLContext around for a `refreshAfter` period, sharing it among connections, then creates a new
   * context. Actually constructing the `SSLEngine` is left to caller, to allow additional customization of the `SSLEngine`,
   * for example to require client certificates in a server application.
   *
   * @param refreshAfter Keep a created context around this long, then recreate it
   * @param construct A factory method to create the context when recreating is needed
   * @return An SSLEngine provider function to use with Akka HTTP `ConnectionContext.httpsServer()` and `ConnectionContext.httpsClient`.
   */
  @ApiMayChange
  def refreshingSSLContextProvider(refreshAfter: FiniteDuration)(construct: () => SSLContext): () => SSLContext = {
    new RefreshableSSLContextReader(refreshAfter, construct)
  }
}
