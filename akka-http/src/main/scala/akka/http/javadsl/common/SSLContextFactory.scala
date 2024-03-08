/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.annotation.ApiMayChange
import akka.http.scaladsl.common.SSLContextFactory.createSSLContextFromPem

import java.nio.file.Path
import javax.net.ssl.SSLContext
import java.util.{ List => JList }
import akka.http.scaladsl.common.{ SSLContextFactory => ScalaSSLContextFactory }
import akka.util.JavaDurationConverters.JavaDurationOps
import com.typesafe.config.Config

import java.security.SecureRandom
import java.time.Duration
import java.util.Optional
import javax.net.ssl.SSLEngine
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional

object SSLContextFactory {

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files defined in config.
   *
   * The provided `Config` is required to have the field `certificate` containing
   * a path to a certificate file, `private-key` containing the path to a private key, and the key `trusted-ca-certificates`
   * either with the value "system" to use the default JDK truststore or containing a list of zero to many paths to CA certificate files
   * to explicitly list what CA certs to trust. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * Example usage: `createSSLContextFromPem(system.settings.config.getConfig("my-server"))`
   *
   * API May Change
   */
  @ApiMayChange
  def createSSLContextFromPem(config: Config): SSLContext = ScalaSSLContextFactory.createSSLContextFromPem(config)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file but use the
   * default JDK trust store. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * API May Change
   */
  @ApiMayChange
  def createSSLContextFromPem(
    certificatePath: Path,
    privateKeyPath:  Path): SSLContext = ScalaSSLContextFactory.createSSLContextFromPem(certificatePath, privateKeyPath)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * API May Change
   */
  @ApiMayChange
  def createSSLContextFromPem(
    certificatePath:           Path,
    privateKeyPath:            Path,
    trustedCaCertificatePaths: JList[Path]): SSLContext =
    ScalaSSLContextFactory.createSSLContextFromPem(certificatePath, privateKeyPath, trustedCaCertificatePaths.asScala.toVector)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and possibly zero or more
   * CA-certificate files to trust. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * @param certificatePath Path to a PEM encoded certificate file
   * @param privateKeyPath Path to a PEM encoded key file
   * @param trustedCaCertificatePaths empty `Optional` to use the default system trust store, or `Optional` with containing a list of
   *                           one or more CA certificate paths to explicitly control exactly what CAs are trusted
   * @param secureRandom a secure random to use for the SSL context or none to use a default instance
   *
   * API May Change
   */
  @ApiMayChange
  def createSSLContextFromPem(
    certificatePath:           Path,
    privateKeyPath:            Path,
    trustedCaCertificatePaths: Optional[Seq[Path]],
    secureRandom:              Optional[SecureRandom]): SSLContext =
    ScalaSSLContextFactory.createSSLContextFromPem(certificatePath, privateKeyPath, trustedCaCertificatePaths.toScala.map(_.toVector), secureRandom.toScala)

  /**
   * Keeps a created SSLContext around for a `refreshAfter` period, sharing it among connections, then creates a new
   * context. Useful for rotating certificates.
   *
   * @param refreshAfter Keep a created context around this long, then recreate it
   * @param construct A factory method to create the context when recreating is needed
   * @return An SSLEngine provider function to use with Akka HTTP `ConnectionContext.httpsServer()` and `ConnectionContext.httpsClient`.
   *
   * API May Change
   */
  @ApiMayChange
  def refreshingSSLEngineProvider(refreshAfter: Duration)(construct: akka.japi.function.Creator[SSLContext]): akka.japi.function.Creator[SSLEngine] =
    () => ScalaSSLContextFactory.refreshingSSLEngineProvider(refreshAfter.asScala)(construct.create _)()

  /**
   * Keeps a created SSLContext around for a `refreshAfter` period, sharing it among connections, then creates a new
   * context. Actually constructing the `SSLEngine` is left to caller, to allow additional customization of the `SSLEngine`,
   * for example to require client certificates in a server application.
   *
   * @param refreshAfter Keep a created context around this long, then recreate it
   * @param construct A factory method to create the context when recreating is needed
   * @return An SSLEngine provider function to use with Akka HTTP `ConnectionContext.httpsServer()` and `ConnectionContext.httpsClient`.
   *
   * API May Change
   */
  @ApiMayChange
  def refreshingSSLContextProvider(refreshAfter: Duration)(construct: akka.japi.function.Creator[SSLContext]): akka.japi.function.Creator[SSLContext] =
    () => ScalaSSLContextFactory.refreshingSSLContextProvider(refreshAfter.asScala)(construct.create _)()
}
