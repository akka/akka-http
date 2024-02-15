package com.lightbend

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.caching.scaladsl.CachingSettings
import akka.http.caching.scaladsl.LfuCacheSettings
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.CachingDirectives
import akka.http.scaladsl.server.directives.CachingDirectives.alwaysCache
import akka.util.Timeout
import com.lightbend.UserRegistry._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq


class Routes(userRegistry: ActorRef[UserRegistry.Command])(implicit val system: ActorSystem[_]) {

  import JsonFormats._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

  val myCache = CachingDirectives.routeCache[Uri](system.classicSystem)

  {
    // some other caches that we wont actually excercise, but to cover different
    // underlying reflection instantiations
    val defaultCachingSettings = CachingSettings(system)
    CachingDirectives.routeCache[Uri](defaultCachingSettings.withLfuCacheSettings(defaultCachingSettings.lfuCacheSettings.withInitialCapacity(2).withMaxCapacity(7)))
    CachingDirectives.routeCache[Uri](defaultCachingSettings.withLfuCacheSettings(defaultCachingSettings.lfuCacheSettings.withTimeToIdle(3.seconds)))
    CachingDirectives.routeCache[Uri](defaultCachingSettings.withLfuCacheSettings(defaultCachingSettings.lfuCacheSettings.withTimeToLive(3.seconds)))
    CachingDirectives.routeCache[Uri](defaultCachingSettings.withLfuCacheSettings(defaultCachingSettings.lfuCacheSettings.withInitialCapacity(2)))
  }

  val simpleKeyer: PartialFunction[RequestContext, Uri] = {
    val isGet: RequestContext => Boolean = _.request.method == GET
    val isAuthorized: RequestContext => Boolean =
      _.request.headers.exists(_.is(Authorization.lowercaseName))
    val result: PartialFunction[RequestContext, Uri] = {
      case r: RequestContext if isGet(r) && !isAuthorized(r) => r.request.uri
    }
    result
  }

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.routes.ask-timeout"))

  def getUsers(): Future[Users] =
    userRegistry.ask(GetUsers.apply)
  def getUser(name: String): Future[GetUserResponse] =
    userRegistry.ask(GetUser(name, _))
  def createUser(user: User): Future[ActionPerformed] =
    userRegistry.ask(CreateUser(user, _))
  def deleteUser(name: String): Future[ActionPerformed] =
    userRegistry.ask(DeleteUser(name, _))

  val userRoutes: Route =
    extractLog(log =>
      concat(
        pathPrefix("users") {
        concat(
          pathEnd {
            concat(
              get {
                complete(getUsers())
              },
              post {
                entity(as[User]) { user =>
                  onSuccess(createUser(user)) { performed =>
                    complete((StatusCodes.Created, performed))
                  }
                }
              })
          },
          path(Segment) { name =>
            concat(
              get {
                rejectEmptyResponse {
                  onSuccess(getUser(name)) { response =>
                    complete(response.maybeUser)
                  }
                }
              },
              delete {
                onSuccess(deleteUser(name)) { performed =>
                  complete((StatusCodes.OK, performed))
                }
              })
          })
      },
      path("gimmieXML") {
        post {
          import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
          entity(as[NodeSeq]) { xmlIn =>
            log.info("Got me some XML: {}", xmlIn)
            complete(<omg><suchXml id="123">Very XML</suchXml></omg>)
          }
        }
      },
      path("cache") {
        alwaysCache(myCache, simpleKeyer) {
          get {
            complete("ok")
          }
        }

      }
    )
  )
}
