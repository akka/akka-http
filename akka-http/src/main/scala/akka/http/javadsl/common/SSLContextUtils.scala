/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.annotation.ApiMayChange

import java.nio.file.Path
import javax.net.ssl.SSLContext
import java.util.{ List => JList }
import akka.http.scaladsl.common.{ SSLContextUtils => ScalaSSLContextUtils }
import akka.util.JavaDurationConverters.JavaDurationOps
import com.typesafe.config.Config

import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.SSLEngine
import scala.jdk.CollectionConverters._

object SSLContextUtils {

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files defined in config. The provided `Config` is required to have the field `certificate` containing
   * a path to a certificate file, `private-key` containing the path to a private key, and the key `ca-certificates`
   * containing a list of zero to many paths to CA certificate files. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * Example usage: `constructSSLContext(system.settings().config().getConfig("my-server"))`
   */
  def constructSSLContext(config: Config): SSLContext = ScalaSSLContextUtils.constructSSLContext(config)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   */
  @ApiMayChange
  def constructSSLContext(
    certificatePath:    Path,
    privateKeyPath:     Path,
    caCertificatePaths: JList[Path]): SSLContext =
    ScalaSSLContextUtils.constructSSLContext(certificatePath, privateKeyPath, caCertificatePaths.asScala.toVector)

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files. All files must contain PEM encoded certificates or keys.
   *
   * Note that the paths are filesystem paths, not class path,
   * certificate files packaged in the JAR cannot be loaded using this method.
   *
   * @param secureRandom a secure random to use for the SSL context
   */
  @ApiMayChange
  def constructSSLContext(
    certificatePath:    Path,
    privateKeyPath:     Path,
    caCertificatePaths: JList[Path],
    secureRandom:       SecureRandom): SSLContext =
    ScalaSSLContextUtils.constructSSLContext(certificatePath, privateKeyPath, caCertificatePaths.asScala.toVector, secureRandom)

  /**
   * Keeps a created SSLContext around for a `refreshAfter` period, sharing it among connections, then creates a new
   * context. Useful for rotating certificates.
   *
   * @param refreshAfter Keep a created context around this long, then recreate it
   * @param construct A factory method to create the context when recreating is needed
   * @return An SSLEngine provider function to use with Akka HTTP `ConnectionContext.httpsServer()` and `ConnectionContext.httpsClient`.
   */
  @ApiMayChange
  def refreshingSSLEngineProvider(refreshAfter: Duration)(construct: akka.japi.function.Creator[SSLContext]): akka.japi.function.Creator[SSLEngine] =
    () => ScalaSSLContextUtils.refreshingSSLEngineProvider(refreshAfter.asScala)(construct.create _)()

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
  def refreshingSSLContextProvider(refreshAfter: Duration)(construct: akka.japi.function.Creator[SSLContext]): akka.japi.function.Creator[SSLContext] =
    () => ScalaSSLContextUtils.refreshingSSLContextProvider(refreshAfter.asScala)(construct.create _)()
}
