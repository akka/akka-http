package com.lightbend

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

object Main {

  private val log = LoggerFactory.getLogger(classOf[Main.type])

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext

    val http = Http(system)
    val futureBinding = http.newServerAt("localhost", 8080).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
        val chainOfTests = http.singleRequest(HttpRequest(uri = "http://localhost:8080/users")).flatMap { response =>
          if (response.status != StatusCodes.OK) throw new RuntimeException(s"Didn't get ok response for user listing ${response.status}")
          else response.entity.toStrict(3.seconds)
        }.flatMap { usersReplyBody =>
          log.info("Users listing {}", usersReplyBody.toString())
          http.singleRequest(HttpRequest(
            method = HttpMethods.POST,
            uri = "http://localhost:8080/users",
            entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString.fromString("""{"name":"Johan","age":25,"countryOfResidence":"Sweden"}"""))))
        }.flatMap(response =>
          if (response.status != StatusCodes.Created) throw new RuntimeException(s"Didn't get created response for creation listing ${response.status}")
          else response.entity.toStrict(3.seconds)
        ).flatMap { createUserReplyBody =>
          log.info("Users created response {}", createUserReplyBody.toString())
          http.singleRequest(HttpRequest(
            method = HttpMethods.GET,
            uri = "http://localhost:8080/users/Johan"))
        }.flatMap(response =>
          if (response.status != StatusCodes.OK) throw new RuntimeException(s"Didn't get ok response for details ${response.status}")
          else response.entity.toStrict(3.seconds)
        ).flatMap { getUserResponseBody =>
          log.info("User get response {}", getUserResponseBody.toString())
          http.singleRequest(HttpRequest(
            method = HttpMethods.DELETE,
            uri = "http://localhost:8080/users/johan"))
        }.flatMap(response =>
          if (response.status != StatusCodes.OK) throw new RuntimeException(s"Didn't get ok response for delete ${response.status}")
          else response.entity.toStrict(3.seconds)
        ).map { userDeletedResponseBody =>
          log.info("User delete response {}", userDeletedResponseBody.toString())
          Done
        }


        chainOfTests.onComplete {
          case Success(_) =>
            log.info("All tests ok, shutting down")
            system.terminate()

          case Failure(error) =>
            log.error("Saw error, test failed", error)
            System.exit(1)
        }



      case Failure(ex) =>
        log.error("Failed to bind HTTP endpoint, terminating system", ex)
        System.exit(1)
    }
  }

  def main(args: Array[String]): Unit = {

    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val userRegistryActor = context.spawn(UserRegistry(), "UserRegistryActor")
      context.watch(userRegistryActor)

      val routes = new UserRoutes(userRegistryActor)(context.system)
      startHttpServer(routes.userRoutes)(context.system)

      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "HelloAkkaHttpServer")
  }
}
