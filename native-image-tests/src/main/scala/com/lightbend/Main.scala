package com.lightbend

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpsConnectionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.ByteString
import org.slf4j.LoggerFactory

import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Failure
import scala.util.Success

object Main {

  private val log = LoggerFactory.getLogger(classOf[Main.type])

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext

    val http = Http(system)
    val futureBinding = http.newServerAt("localhost", 8080).bind(routes)
    val javaHttp = akka.http.javadsl.Http.get(system)
    val futureJavaBinding = javaHttp.newServerAt("localhost", 8081).bind(JavaRoutes.javaRoutes())

    val (serverHttpsConnectionContext, clientHttpsConnectionContext) = createHttpsConnectionContexts()
    val tlsBinding = http.newServerAt("localhost", 8443).enableHttps(serverHttpsConnectionContext).bind(routes)
    http.setDefaultClientHttpsContext(clientHttpsConnectionContext)

    val allThreeServers = futureBinding.zip(futureJavaBinding.asScala).zip(tlsBinding).map { case ((a, b), c) => (a, b, c) }

    def requestOrFail(operationDescription: String, request: HttpRequest, expectedResponse: StatusCode): Future[Unit] =
      http.singleRequest(request).flatMap(response => response.entity.toStrict(3.seconds).map { strictEntity =>
        val body = strictEntity.data.utf8String
        if (response.status != expectedResponse) throw new RuntimeException(s"Didn't get $expectedResponse response for $operationDescription but ${response.status}, body: $body")
        else log.info("{} successful, response status {}, body: {}", operationDescription, response.status, body)
      })

    allThreeServers.onComplete {
      case Success((binding, javaBinding, tlsBinding)) =>
        log.info("Server online at http://{}:{}/, http://{}:{} and https://{}:{}",
          binding.localAddress.getHostString,
          binding.localAddress.getPort,
          javaBinding.localAddress.getHostString,
          javaBinding.localAddress.getPort,
          tlsBinding.localAddress.getHostString,
          tlsBinding.localAddress.getPort
        )
        binding.addToCoordinatedShutdown(3.seconds)
        javaBinding.addToCoordinatedShutdown(3.seconds.toJava, system)
        tlsBinding.addToCoordinatedShutdown(3.seconds)

        val chainOfTests = for {
          _ <- requestOrFail("User listing", HttpRequest(uri = "http://localhost:8080/users"), StatusCodes.OK)
          _ <- requestOrFail("User update/creation",
            HttpRequest(
              method = HttpMethods.POST,
              uri = "http://localhost:8080/users",
              entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString.fromString("""{"name":"Johan","age":25,"countryOfResidence":"Sweden"}"""))),
            StatusCodes.Created)
          _ <- requestOrFail("Get user details", HttpRequest(
            method = HttpMethods.GET,
            uri = "http://localhost:8080/users/Johan"), StatusCodes.OK)
          _ <- requestOrFail("Delete user", HttpRequest(
            method = HttpMethods.DELETE,
            uri = "http://localhost:8080/users/Johan"), StatusCodes.OK)
          _ <- requestOrFail("akka-http-xml, XML in/out", HttpRequest(
            method = HttpMethods.POST,
            uri = "http://localhost:8080/gimmieXML",
            entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<?xml version=\"1.0\"?>\n<somePrettyGoodXml></somePrettyGoodXml>")), StatusCodes.OK)
          _ <- requestOrFail("akka-http-cache", HttpRequest(
            method = HttpMethods.GET,
            uri = "http://localhost:8080/cache",
          ), StatusCodes.OK)
          _ <- requestOrFail("akka-http-cache", HttpRequest(
            method = HttpMethods.GET,
            uri = "http://localhost:8080/cache",
          ), StatusCodes.OK)
          _ <- requestOrFail("akka-http-jackson (Java)",
            HttpRequest(uri = "https://localhost:8443/users"), StatusCodes.OK)
          _ <- requestOrFail("HTTPS",
            HttpRequest(uri = "https://localhost:8443/users"), StatusCodes.OK)
        } yield Done

        chainOfTests.onComplete {
          case Success(_) =>
            if (!sys.env.contains("KEEP_RUNNING")) {
              log.info("All tests ok, shutting down")
              system.terminate()
            }

          case Failure(error) =>
            println("Saw error, test failed")
            error.printStackTrace()
            System.exit(1)
        }

      case Failure(ex) =>
        log.error("Failed to bind HTTP endpoint, terminating system", ex)
        System.exit(1)
    }
  }



  def createHttpsConnectionContexts():  (HttpsConnectionContext, HttpsConnectionContext) = {
    val password: Array[Char] = "password".toCharArray

    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("keystore.p12")

    require(keystore != null, "Keystore not found!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    (ConnectionContext.httpsServer(sslContext), ConnectionContext.httpsClient(sslContext))
  }

  def main(args: Array[String]): Unit = {

    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val userRegistryActor = context.spawn(UserRegistry(), "UserRegistryActor")
      context.watch(userRegistryActor)

      val routes = new Routes(userRegistryActor)(context.system)
      startHttpServer(routes.userRoutes)(context.system)

      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "HelloAkkaHttpServer")
  }
}
