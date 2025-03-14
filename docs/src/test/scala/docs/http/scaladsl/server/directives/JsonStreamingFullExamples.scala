/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import org.scalatest.wordspec.AnyWordSpec

class JsonStreamingFullExamples extends AnyWordSpec {

  "compile only spec" in {}

  //#custom-content-type
  import akka.NotUsed
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
  import akka.http.scaladsl.model.{ HttpEntity, _ }
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
  import akka.stream.scaladsl.Source
  import spray.json.DefaultJsonProtocol

  import scala.concurrent.ExecutionContext
  import scala.io.StdIn
  import scala.util.Random

  final case class User(name: String, id: String)

  trait UserProtocol extends DefaultJsonProtocol {

    import spray.json._

    implicit val userFormat: JsonFormat[User] = jsonFormat2(User.apply)

    val `vnd.example.api.v1+json` =
      MediaType.applicationWithFixedCharset("vnd.example.api.v1+json", HttpCharsets.`UTF-8`)
    val ct = ContentType.apply(`vnd.example.api.v1+json`)

    implicit def userMarshaller: ToEntityMarshaller[User] = Marshaller.oneOf(
      Marshaller.withFixedContentType(`vnd.example.api.v1+json`) { (user: User) =>
        HttpEntity(`vnd.example.api.v1+json`, user.toJson.compactPrint)
      })
  }

  object ApiServer extends App with UserProtocol {
    implicit val system: ActorSystem = ActorSystem("api")
    implicit val executionContext: ExecutionContext = system.dispatcher

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
      .withContentType(ct)
      .withParallelMarshalling(parallelism = 10, unordered = false)

    // (fake) async database query api
    def dummyUser(id: String) = User(s"User $id", id.toString)

    def fetchUsers(): Source[User, NotUsed] = Source.fromIterator(() => Iterator.fill(10000) {
      val id = Random.nextInt()
      dummyUser(id.toString)
    })

    val route =
      pathPrefix("users") {
        get {
          complete(fetchUsers())
        }
      }

    val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine()
    bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
  }

  //#custom-content-type
}
