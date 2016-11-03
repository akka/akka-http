/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl._

import akka.actor.ActorSystem
import akka.http.impl.engine.http2.WrappedSslContextSPI
import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.scaladsl.FileIO
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.sslconfig.akka.{ AkkaSSLConfig, DefaultSSLEngineConfigurator }
import example.Ciphers
import io.netty.buffer.PooledByteBufAllocator
import io.netty.handler.ssl.ApplicationProtocolConfig.{ Protocol, SelectedListenerFailureBehavior, SelectorFailureBehavior }
import io.netty.handler.ssl.util.SelfSignedCertificate

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Random

object Http2ServerTest extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    akka.actor.serialize-creators = off
    akka.actor.serialize-messages = off
    #akka.actor.default-dispatcher.throughput = 1000
    akka.actor.default-dispatcher.fork-join-executor.parallelism-max=8
                                                   """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val settings = ActorMaterializerSettings(system)
    .withFuzzing(false)
    //    .withSyncProcessingLimit(Int.MaxValue)
    .withInputBuffer(128, 128)
  implicit val fm = ActorMaterializer(settings)

  def slowDown[T](millis: Int): T ⇒ Future[T] = { t ⇒
    akka.pattern.after(millis.millis, system.scheduler)(Future.successful(t))
  }

  val syncHandler: HttpRequest ⇒ HttpResponse = {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)           ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)       ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/image-page"), _, _, _) ⇒ imagePage
    case HttpRequest(GET, Uri(_, _, p, _, _), _, _, _) if p.toString.startsWith("/image1") ⇒
      HttpResponse(entity = HttpEntity(MediaTypes.`image/jpeg`, FileIO.fromPath(Paths.get("bigimage.jpg"), 100000).mapAsync(1)(slowDown(1))))
    case HttpRequest(GET, Uri(_, _, p, _, _), _, _, _) if p.toString.startsWith("/image2") ⇒
      HttpResponse(entity = HttpEntity(MediaTypes.`image/jpeg`, FileIO.fromPath(Paths.get("bigimage2.jpg"), 150000).mapAsync(1)(slowDown(2))))
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
  }

  val asyncHandler: HttpRequest ⇒ Future[HttpResponse] =
    req ⇒ Future.successful(syncHandler(req))

  val context = {
    val nettySslContext = {
      // never put passwords into code!
      val password = "abcdef".toCharArray

      val ks = KeyStore.getInstance("PKCS12")
      ks.load(ExampleHttpContexts.resourceStream("keys/server.p12"), password)

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(ks, password)

      val ssc = new SelfSignedCertificate

      import io.netty.handler.ssl._
      SslContextBuilder.forServer(
        ssc.certificate(), ssc.privateKey()
      //new File("/home/johannes/git/opensource/akka-http/akka-http-core/src/test/resources/keys/chain.pem"),
      //new File("/home/johannes/git/opensource/akka-http/akka-http-core/src/test/resources/keys/pkcs8_key.pem")
      /*,"abcdef"*/
      )
        .sslProvider(SslProvider.OPENSSL)
        .ciphers(Ciphers.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(new ApplicationProtocolConfig(
          Protocol.NPN_AND_ALPN,
          // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
          SelectorFailureBehavior.NO_ADVERTISE,
          // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
          SelectedListenerFailureBehavior.ACCEPT,
          ApplicationProtocolNames.HTTP_2,
          ApplicationProtocolNames.HTTP_1_1))
        .build()
    }

    def wrapContext(context: HttpsConnectionContext): HttpsConnectionContext = {
      val newContext = SSLContext.getInstance("TLS")
      WrappedSslContextSPI.field.set(newContext, new WrappedSslContextSPI(context.sslContext) {
        override def engineCreateSSLEngine(): SSLEngine = {
          val delegate = nettySslContext.newEngine(PooledByteBufAllocator.DEFAULT)
          new SSLEngine() {
            def closeOutbound(): Unit = delegate.closeOutbound()
            def beginHandshake(): Unit = delegate.beginHandshake()

            def getNeedClientAuth: Boolean = delegate.getNeedClientAuth
            def getWantClientAuth: Boolean = delegate.getWantClientAuth
            def unwrap(byteBuffer: ByteBuffer, byteBuffers: Array[ByteBuffer], i: Int, i1: Int): SSLEngineResult =
              delegate.unwrap(byteBuffer, byteBuffers, i, i1)

            def getEnableSessionCreation: Boolean = delegate.getEnableSessionCreation
            def getSupportedCipherSuites: Array[String] = delegate.getSupportedCipherSuites

            def isInboundDone: Boolean = delegate.isInboundDone

            def closeInbound(): Unit = delegate.closeInbound()
            def getSupportedProtocols: Array[String] = delegate.getSupportedProtocols
            def getDelegatedTask: Runnable = delegate.getDelegatedTask
            def getHandshakeStatus: HandshakeStatus = delegate.getHandshakeStatus
            def wrap(byteBuffers: Array[ByteBuffer], i: Int, i1: Int, byteBuffer: ByteBuffer): SSLEngineResult =
              delegate.wrap(byteBuffers, i, i1, byteBuffer)
            def getSession: SSLSession = delegate.getSession
            def getEnabledProtocols: Array[String] = delegate.getEnabledProtocols
            def isOutboundDone: Boolean = delegate.isOutboundDone
            def getEnabledCipherSuites: Array[String] = delegate.getEnabledCipherSuites
            def getUseClientMode: Boolean = delegate.getUseClientMode

            def setEnableSessionCreation(b: Boolean): Unit = ()
            def setNeedClientAuth(b: Boolean): Unit = ()
            def setWantClientAuth(b: Boolean): Unit = ()
            def setEnabledProtocols(strings: Array[String]): Unit = ()
            def setEnabledCipherSuites(strings: Array[String]): Unit = ()
            def setUseClientMode(b: Boolean): Unit = ()
          }
        }
      })

      new HttpsConnectionContext(
        newContext,
        None,
        None,
        None
      )
    }

    wrapContext(ExampleHttpContexts.exampleServerContext)
  }

  try {
    val bindings =
      for {
        binding1 ← Http().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 9000, ExampleHttpContexts.exampleServerContext)
        binding2 ← Http2().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 9001, ExampleHttpContexts.exampleServerContext)
        binding3 ← Http2().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 9002, context)
      } yield (binding1, binding2, binding3)

    Await.result(bindings, 1.second) // throws if binding fails
    println("Server online at http://localhost:9001")
    println("Press RETURN to stop...")
    StdIn.readLine()
  } finally {
    system.terminate()
  }

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """|<html>
        | <body>
        |    <h1>Say hello to <i>akka-http-core</i>!</h1>
        |    <p>Defined resources:</p>
        |    <ul>
        |      <li><a href="/ping">/ping</a></li>
        |      <li><a href="/image-page">/image-page</a></li>
        |      <li><a href="/crash">/crash</a></li>
        |    </ul>
        |  </body>
        |</html>""".stripMargin))

  def imagesBlock = {
    def one(): String =
      s"""<img width="80" height="60" src="/image1?cachebuster=${Random.nextInt}"></img>
         |<img width="80" height="60" src="/image2?cachebuster=${Random.nextInt}"></img>
         |""".stripMargin

    Seq.fill(20)(one()).mkString
  }

  lazy val imagePage = HttpResponse(
    entity = HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      s"""|<html>
          | <body>
          |    <h1>Image Page</h1>
          |    $imagesBlock
          |  </body>
          |</html>""".stripMargin))
}
