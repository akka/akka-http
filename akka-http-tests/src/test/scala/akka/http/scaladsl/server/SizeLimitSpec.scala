/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.collection.immutable

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{ `Content-Encoding`, HttpEncodings }
import akka.stream.ActorMaterializer
import akka.testkit.{ EventFilter, TestKit }
import akka.util.ByteString

import com.typesafe.config.{ Config, ConfigFactory }

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

class SizeLimitSpec extends WordSpec with Matchers with RequestBuilding with BeforeAndAfterAll with ScalaFutures {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    akka.http.parsing.max-content-length = 800
    akka.http.routing.decode-max-size = 800
    """)
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  implicit val defaultPatience = PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  "a normal route" should {
    val route = path("noDirective") {
      post {
        entity(as[String]) { _ ⇒
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        }
      }
    }

    val binding = Http().bindAndHandle(route, "localhost", port = 0).futureValue

    "accept small POST requests" in {
      Http().singleRequest(Post(s"http:/${binding.localAddress}/noDirective", entityOfSize(700)))
        .futureValue.status shouldEqual StatusCodes.OK
    }

    "not accept entities bigger than configured with akka.http.parsing.max-content-length" in {
      // It went from 1 occurrence to 2 after discarding the entity, I think is due to the retrying nature of `handleRejections`
      // that causes one Exception for the original entity and another one from the rejected one.
      EventFilter[EntityStreamSizeException](occurrences = 2).intercept {
        Http().singleRequest(Post(s"http:/${binding.localAddress}/noDirective", entityOfSize(801)))
          .futureValue.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  "a route with decodeRequest" should {
    val route = path("noDirective") {
      decodeRequest {
        post {
          entity(as[String]) { e ⇒
            println(s"Got request with entity of ${e.length} characters")
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
      }
    }

    val binding = Http().bindAndHandle(route, "localhost", port = 0).futureValue

    "reject a small request decodes into a large entity" in {

      val request = HttpRequest(
        HttpMethods.POST,
        s"http:/${binding.localAddress}/noDirective",
        immutable.Seq(`Content-Encoding`(HttpEncodings.gzip)),
        HttpEntity(ContentTypes.`text/plain(UTF-8)`, Gzip.encode(ByteString.fromString("0" * 801))))

      Http().singleRequest(request)
        .futureValue.status shouldEqual StatusCodes.BadRequest
    }
  }

  "the withoutSizeLimit directive" should {
    val route = path("withoutSizeLimit") {
      post {
        withoutSizeLimit {
          entity(as[String]) { _ ⇒
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
      }
    }

    val binding = Http().bindAndHandle(route, "localhost", port = 0).futureValue

    "accept entities bigger than configured with akka.http.parsing.max-content-length" in {
      Http().singleRequest(Post(s"http:/${binding.localAddress}/withoutSizeLimit", entityOfSize(801)))
        .futureValue.status shouldEqual StatusCodes.OK
    }
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  private def entityOfSize(size: Int) = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "0" * size)
}
