/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.Matchers
import org.scalatest.WordSpec

class HttpServerWithTypedSpec extends WordSpec with Matchers with CompileOnlySpec {

  "akka-typed-example" in compileOnlySpec {
    //#akka-typed-behavior
    import akka.actor.typed.{ ActorRef, Behavior }
    import akka.actor.typed.scaladsl.Behaviors

    // Definition of the case class representing a build job and its possible status values
    sealed trait Status
    final object Successful extends Status
    final object Failed extends Status

    final case class Job(id: Long, projectName: String, status: Status, duration: Long)
    final case class Jobs(jobs: Seq[Job])

    object BuildJobRepository {

      // Trait defining successful and failure responses
      sealed trait Response
      case object OK extends Response
      final case class KO(reason: String) extends Response

      // Trait and its implementations representing all possible messages that can be sent to this Behavior
      sealed trait BuildJobProtocol
      final case class AddJob(job: Job, replyTo: ActorRef[Response]) extends BuildJobProtocol
      final case class GetJobById(id: Long, replyTo: ActorRef[Option[Job]]) extends BuildJobProtocol
      final case class GetJobByStatus(status: Status, replyTo: ActorRef[Seq[Job]]) extends BuildJobProtocol
      final case class ClearJobs(replyTo: ActorRef[Response]) extends BuildJobProtocol

      // This behavior handles all possible incoming messages and keeps the state in the function parameter
      def apply(jobs: Map[Long, Job] = Map.empty): Behavior[BuildJobProtocol] = Behaviors.receiveMessage {
        case AddJob(job, replyTo) if jobs.contains(job.id) =>
          replyTo ! KO("Job already exists")
          Behaviors.same
        case AddJob(job, replyTo) =>
          replyTo ! OK
          BuildJobRepository(jobs.+(job.id -> job))
        case GetJobById(id, replyTo) =>
          replyTo ! jobs.get(id)
          Behaviors.same
        case ClearJobs(replyTo) =>
          replyTo ! OK
          BuildJobRepository(Map.empty)
      }

    }
    //#akka-typed-behavior

    //#akka-typed-json
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    import spray.json.DefaultJsonProtocol
    import spray.json.DeserializationException
    import spray.json.JsString
    import spray.json.JsValue
    import spray.json.RootJsonFormat

    trait JsonSupport extends SprayJsonSupport {
      // import the default encoders for primitive types (Int, String, Lists etc)
      import DefaultJsonProtocol._

      implicit object StatusFormat extends RootJsonFormat[Status] {
        def write(status: Status): JsValue = status match {
          case Failed     => JsString("Failed")
          case Successful => JsString("Successful")
        }

        def read(json: JsValue): Status = json match {
          case JsString("Failed")     => Failed
          case JsString("Successful") => Successful
          case _                      => throw new DeserializationException("Status unexpected")
        }
      }

      implicit val jobFormat = jsonFormat4(Job)

      implicit val jobsFormat = jsonFormat1(Jobs)
    }
    //#akka-typed-json

    //#akka-typed-route
    import akka.actor.typed.ActorSystem
    import akka.util.Timeout

    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.StatusCodes
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.server.directives.MethodDirectives.delete
    import akka.http.scaladsl.server.directives.MethodDirectives.post
    import akka.http.scaladsl.server.directives.RouteDirectives.complete
    import akka.http.scaladsl.server.directives.PathDirectives.path

    import scala.concurrent.duration._
    import scala.concurrent.Future
    import BuildJobRepository._

    class JobRoutes(system: ActorSystem[_], buildJobRepository: ActorRef[BuildJobProtocol]) extends JsonSupport {

      import akka.actor.typed.scaladsl.AskPattern._

      // asking someone requires a timeout and a scheduler, if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout: Timeout = 3.seconds
      implicit val scheduler = system.scheduler

      lazy val theJobRoutes: Route =
        pathPrefix("jobs") {
          concat(
            pathEnd {
              concat(
                post {
                  entity(as[Job]) { job =>
                    val operationPerformed: Future[Response] = buildJobRepository.ask(replyTo => AddJob(job, replyTo))
                    onSuccess(operationPerformed) {
                      case OK         => complete("Job added")
                      case KO(reason) => complete(StatusCodes.InternalServerError -> reason)
                    }
                  }
                },
                delete {
                  val operationPerformed: Future[Response] = buildJobRepository.ask(replyTo => ClearJobs(replyTo))
                  onSuccess(operationPerformed) {
                    case OK         => complete("Jobs cleared")
                    case KO(reason) => complete(StatusCodes.InternalServerError -> reason)
                  }
                }
              )
            },
            (get & path(LongNumber)) { id =>
              val maybeJob: Future[Option[Job]] = buildJobRepository.ask(replyTo => GetJobById(id, replyTo))
              rejectEmptyResponse {
                complete(maybeJob)
              }
            }
          )
        }
    }
    //#akka-typed-route


    /* FIXME currently not compiled because toUntyped in Akka 2.5 was renamed to toClassic in Akka 2.6
    //#akka-typed-bootstrap
    import akka.{ actor, Done }
    import akka.actor.typed.scaladsl.adapter._
    import akka.stream.ActorMaterializer

    import akka.http.scaladsl.Http

    import scala.concurrent.ExecutionContextExecutor
    import scala.util.{ Success, Failure }

    val system = ActorSystem[Done](Behaviors.setup[Done] { ctx =>
      // http doesn't know about akka typed so create untyped system/materializer
      implicit val untypedSystem: actor.ActorSystem = ctx.system.toUntyped
      implicit val materializer: ActorMaterializer = ActorMaterializer()(ctx.system.toClassic)
      implicit val ec: ExecutionContextExecutor = ctx.system.executionContext

      val buildJobRepository = ctx.spawn(BuildJobRepository(), "BuildJobRepositoryActor")

      val routes = new JobRoutes(ctx.system, buildJobRepository)

      val serverBinding: Future[Http.ServerBinding] = Http()(untypedSystem).bindAndHandle(routes.theJobRoutes, "localhost", 8080)
      serverBinding.onComplete {
        case Success(bound) =>
          println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
        case Failure(e) =>
          Console.err.println(s"Server could not start!")
          e.printStackTrace()
          ctx.self ! Done
      }
      Behaviors.receiveMessage {
        case Done =>
          Behaviors.stopped
      }

    }, "BuildJobsServer")

    //#akka-typed-bootstrap
    */
  }
}
