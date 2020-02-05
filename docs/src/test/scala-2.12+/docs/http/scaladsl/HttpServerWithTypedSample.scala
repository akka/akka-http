/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

object HttpServerWithTypedSample {

  //#akka-typed-behavior
  import akka.actor.typed.{ ActorRef, Behavior }
  import akka.actor.typed.scaladsl.Behaviors

  object JobRepository {

    // Definition of the a build job and its possible status values
    sealed trait Status
    object Successful extends Status
    object Failed extends Status

    final case class Job(id: Long, projectName: String, status: Status, duration: Long)
    final case class Jobs(jobs: Seq[Job])

    // Trait defining successful and failure responses
    sealed trait Response
    case object OK extends Response
    final case class KO(reason: String) extends Response

    // Trait and its implementations representing all possible messages that can be sent to this Behavior
    sealed trait Command
    final case class AddJob(job: Job, replyTo: ActorRef[Response]) extends Command
    final case class GetJobById(id: Long, replyTo: ActorRef[Option[Job]]) extends Command
    final case class GetJobByStatus(status: Status, replyTo: ActorRef[Seq[Job]]) extends Command
    final case class ClearJobs(replyTo: ActorRef[Response]) extends Command

    // This behavior handles all possible incoming messages and keeps the state in the function parameter
    def apply(jobs: Map[Long, Job] = Map.empty): Behavior[Command] = Behaviors.receiveMessage {
      case AddJob(job, replyTo) if jobs.contains(job.id) =>
        replyTo ! KO("Job already exists")
        Behaviors.same
      case AddJob(job, replyTo) =>
        replyTo ! OK
        JobRepository(jobs.+(job.id -> job))
      case GetJobById(id, replyTo) =>
        replyTo ! jobs.get(id)
        Behaviors.same
      case ClearJobs(replyTo) =>
        replyTo ! OK
        JobRepository(Map.empty)
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
    import JobRepository._

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

  import scala.concurrent.duration._
  import scala.concurrent.Future

  class JobRoutes(buildJobRepository: ActorRef[JobRepository.Command])(implicit system: ActorSystem[_]) extends JsonSupport {

    import akka.actor.typed.scaladsl.AskPattern.Askable

    // asking someone requires a timeout and a scheduler, if the timeout hits without response
    // the ask is failed with a TimeoutException
    implicit val timeout: Timeout = 3.seconds
    // implicit scheduler only needed in 2.5
    // in 2.6 having an implicit typed ActorSystem in scope is enough if you import AskPattern.schedulerFromActorSystem
    implicit val scheduler = system.scheduler

    lazy val theJobRoutes: Route =
      pathPrefix("jobs") {
        concat(
          pathEnd {
            concat(
              post {
                entity(as[JobRepository.Job]) { job =>
                  val operationPerformed: Future[JobRepository.Response] =
                    buildJobRepository.ask(JobRepository.AddJob(job, _))
                  onSuccess(operationPerformed) {
                    case JobRepository.OK         => complete("Job added")
                    case JobRepository.KO(reason) => complete(StatusCodes.InternalServerError -> reason)
                  }
                }
              },
              delete {
                val operationPerformed: Future[JobRepository.Response] =
                  buildJobRepository.ask(JobRepository.ClearJobs(_))
                onSuccess(operationPerformed) {
                  case JobRepository.OK         => complete("Jobs cleared")
                  case JobRepository.KO(reason) => complete(StatusCodes.InternalServerError -> reason)
                }
              }
            )
          },
          (get & path(LongNumber)) { id =>
            val maybeJob: Future[Option[JobRepository.Job]] =
              buildJobRepository.ask(JobRepository.GetJobById(id, _))
            rejectEmptyResponse {
              complete(maybeJob)
            }
          }
        )
      }
  }
  //#akka-typed-route

  /* there are still differences making this impossible to compile both for 2.5 and 2.6 at the same time
     without passing implicits explicitly and making the sample somewhat weird. This is however verified
     against 2.6.0-RC1 with those implicits noted removed.

  //#akka-typed-bootstrap
  import akka.actor.typed.PostStop
  import akka.actor.typed.scaladsl.adapter._
  import akka.stream.ActorMaterializer
  import akka.http.scaladsl.Http.ServerBinding
  import akka.http.scaladsl.Http

  import scala.concurrent.ExecutionContextExecutor
  import scala.util.{ Success, Failure }

  object Server {

    sealed trait Message
    private final case class StartFailed(cause: Throwable) extends Message
    private final case class Started(binding: ServerBinding) extends Message
    case object Stop extends Message

    def apply(host: String, port: Int): Behavior[Message] = Behaviors.setup { ctx =>

      implicit val system = ctx.system
      // http doesn't know about akka typed so provide untyped system
      implicit val untypedSystem: akka.actor.ActorSystem = ctx.system.toClassic
      // implicit materializer only required in Akka 2.5
      // in 2.6 having an implicit classic or typed ActorSystem in scope is enough
      implicit val materializer: ActorMaterializer = ActorMaterializer()(ctx.system.toClassic)
      implicit val ec: ExecutionContextExecutor = ctx.system.executionContext

      val buildJobRepository = ctx.spawn(JobRepository(), "JobRepository")
      val routes = new JobRoutes(buildJobRepository)

      val serverBinding: Future[Http.ServerBinding] =
        Http.apply().bindAndHandle(routes.theJobRoutes, host, port)
      ctx.pipeToSelf(serverBinding) {
        case Success(binding) => Started(binding)
        case Failure(ex)      => StartFailed(ex)
      }

      def running(binding: ServerBinding): Behavior[Message] =
        Behaviors.receiveMessagePartial[Message] {
          case Stop =>
            ctx.log.info(
              "Stopping server http://{}:{}/",
              binding.localAddress.getHostString,
              binding.localAddress.getPort)
            Behaviors.stopped
        }.receiveSignal {
          case (_, PostStop) =>
            binding.unbind()
            Behaviors.same
        }

      def starting(wasStopped: Boolean): Behaviors.Receive[Message] =
        Behaviors.receiveMessage[Message] {
          case StartFailed(cause) =>
            throw new RuntimeException("Server failed to start", cause)
          case Started(binding) =>
            ctx.log.info(
              "Server online at http://{}:{}/",
              binding.localAddress.getHostString,
              binding.localAddress.getPort)
            if (wasStopped) ctx.self ! Stop
            running(binding)
          case Stop =>
            // we got a stop message but haven't completed starting yet,
            // we cannot stop until starting has completed
            starting(wasStopped = true)
        }

      starting(wasStopped = false)
    }
  }

  def main(args: Array[String]) {
    val system: ActorSystem[Server.Message] =
      ActorSystem(Server("localhost", 8080), "BuildJobsServer")
  }
  //#akka-typed-bootstrap

  */
}
