/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.javadsl.common

import akka.annotation.ApiMayChange

import java.nio.file.Path
import javax.net.ssl.SSLContext
import java.util.{ List => JList }
import akka.http.scaladsl.common.{ SSLContextUtils => ScalaSSLContextUtils }
import akka.util.JavaDurationConverters.JavaDurationOps

import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.SSLEngine
import scala.jdk.CollectionConverters._

object SSLContextUtils {

  /**
   * Convenience factory for constructing an SSLContext out of a certificate file, a private key file and zero or more
   * CA-certificate files.
   */
  @ApiMayChange
  def constructSSLContext(
    certificatePath:    Path,
    privateKeyPath:     Path,
    caCertificatePaths: JList[Path]): SSLContext =
    ScalaSSLContextUtils.constructSSLContext(certificatePath, privateKeyPath, caCertificatePaths.asScala.toVector)

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
