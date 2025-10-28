/*
 * Copyright (C) 2020-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.http2.client

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.{ AttributeKey, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCodes }
import akka.http.scaladsl.settings.Http2ClientSettings
import akka.stream.scaladsl.{ Flow, Keep, Source }
import akka.stream.stage.TimerGraphStageLogic
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet, StreamTcpException }
import akka.util.PrettyDuration

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

/** INTERNAL API */
@InternalApi
private[http2] object PersistentConnection {

  private case class EmbargoEnded(connectsLeft: Option[Int], embargo: FiniteDuration)

  /**
   * Wraps a connection flow with transparent reconnection support.
   *
   * Backpressure logic:
   *
   *  * when unconnected wait for pull on response side to pull request side
   *  * when first request comes in, establish connection and send initial request to connection
   *  * connection then drives backpressure logic, i.e.
   *    * it may pull regardless of outer demand
   *    * if it pulls and runs multiple requests concurrently, it must also buffer them internally
   *    * this stage will only ever buffer requests directly in the incoming request slot
   *    * outgoing responses are never buffer but we pull the connection only if there is outer demand
   *
   * Error reporting logic when connection breaks while requests are running:
   *  * generate error responses with 502 status code
   *  * custom attribute contains internal error information
   */
  def managedConnection(connectionFlow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], settings: Http2ClientSettings): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow.fromGraph(new Stage(connectionFlow, settings.maxPersistentAttempts match {
      case 0 => None
      case n => Some(n)
    }, settings.baseConnectionBackoff, settings.maxConnectionBackoff))

  private class AssociationTag extends RequestResponseAssociation
  private val associationTagKey = AttributeKey[AssociationTag]("PersistentConnection.associationTagKey")
  private val errorResponse =
    HttpResponse(
      StatusCodes.BadGateway,
      entity = "The server closed the connection before delivering a response.")

  private class Stage(connectionFlow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], maxAttempts: Option[Int], baseEmbargo: FiniteDuration, _maxBackoff: FiniteDuration) extends GraphStage[FlowShape[HttpRequest, HttpResponse]] {
    val requestIn = Inlet[HttpRequest]("PersistentConnection.requestIn")
    val responseOut = Outlet[HttpResponse]("PersistentConnection.responseOut")
    val maxBaseEmbargo = _maxBackoff / 2 // because we'll add a random component of the same size to the base

    val shape: FlowShape[HttpRequest, HttpResponse] = FlowShape(requestIn, responseOut)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with StageLogging {
      become(Unconnected)

      def become(state: State): Unit = setHandlers(requestIn, responseOut, state)

      trait State extends InHandler with OutHandler
      object Unconnected extends State {
        override def onPush(): Unit = connect(maxAttempts, Duration.Zero)
        override def onPull(): Unit =
          if (!isAvailable(requestIn) && !hasBeenPulled(requestIn)) // requestIn might already have been pulled when we failed and went back to Unconnected
            pull(requestIn)
      }

      def connect(connectsLeft: Option[Int], lastEmbargo: FiniteDuration): Unit = {
        val requestOut = new SubSourceOutlet[HttpRequest]("PersistentConnection.requestOut")
        val responseIn = new SubSinkInlet[HttpResponse]("PersistentConnection.responseIn")
        val connection = Promise[OutgoingConnection]()

        become(new Connecting(connection.future, requestOut, responseIn, connectsLeft.map(_ - 1), lastEmbargo))

        connection.completeWith(Source.fromGraph(requestOut.source)
          .viaMat(connectionFlow)(Keep.right)
          .toMat(responseIn.sink)(Keep.left)
          .run()(subFusingMaterializer))
      }

      class Connecting(
        connected:    Future[OutgoingConnection],
        requestOut:   SubSourceOutlet[HttpRequest],
        responseIn:   SubSinkInlet[HttpResponse],
        connectsLeft: Option[Int],
        lastEmbargo:  FiniteDuration
      ) extends State {
        connected.onComplete({
          case Success(_) =>
            onConnected.invoke(())
          case Failure(cause) =>
            onFailed.invoke(cause)
        })(ExecutionContext.parasitic)

        var requestOutPulled = false
        requestOut.setHandler(new OutHandler {
          override def onPull(): Unit =
            requestOutPulled = true
          override def onDownstreamFinish(cause: Throwable): Unit = ()
        })
        responseIn.setHandler(new InHandler {
          override def onPush(): Unit = throw new IllegalStateException("no response push expected while connecting")
          override def onUpstreamFinish(): Unit = ()
          override def onUpstreamFailure(ex: Throwable): Unit = ()
        })

        override def onPush(): Unit = () // Pull might have happened before the connection failed. Element is kept in slot.

        override def onPull(): Unit = {
          if (!isAvailable(requestIn) && !hasBeenPulled(requestIn)) // requestIn might already have been pulled when we failed and went back to Unconnected
            pull(requestIn)
        }

        val onConnected = getAsyncCallback[Unit] { _ =>
          val newState = new Connected(requestOut, responseIn)
          become(newState)
          if (requestOutPulled) {
            if (isAvailable(requestIn)) newState.dispatchRequest(grab(requestIn))
            else if (!hasBeenPulled(requestIn)) pull(requestIn)
          }
        }
        val onFailed = getAsyncCallback[Throwable] { cause =>
          // If the materialized value is failed, then the stream should be broken by design.
          // Nevertheless also kick our ends of the stream.
          responseIn.cancel()
          requestOut.fail(new StreamTcpException("connection broken"))

          if (connectsLeft.contains(0)) {
            if (isAvailable(requestIn)) {
              // fail the triggering request before failing the stream
              val request = grab(requestIn)
              val response = HttpResponse(
                StatusCodes.ServiceUnavailable,
                entity = s"Connection failed after $maxAttempts attempts")
                .withAttributes(request.attributes)
              emit(responseOut, response, () =>
                failStage(new RuntimeException(s"Connection failed after $maxAttempts attempts", cause))
              )
            } else {
              failStage(new RuntimeException(s"Connection failed after $maxAttempts attempts", cause))
            }
          } else {
            setHandler(requestIn, Unconnected)
            if (baseEmbargo == Duration.Zero) {
              log.info(s"Connection attempt failed: ${cause.getMessage}. Trying to connect again${connectsLeft.map(n => s" ($n attempts left)").getOrElse("")}.")
              connect(connectsLeft, Duration.Zero)
            } else {
              val embargo = lastEmbargo match {
                case Duration.Zero => baseEmbargo
                case otherValue    => (otherValue * 2).min(maxBaseEmbargo)
              }
              val minMillis = embargo.toMillis
              val maxMillis = minMillis * 2
              val backoff = ThreadLocalRandom.current().nextLong(minMillis, maxMillis).millis
              log.info(s"Connection attempt failed: ${cause.getMessage}. Trying to connect again after backoff ${PrettyDuration.format(backoff)} ${connectsLeft.map(n => s" ($n attempts left)").getOrElse("")}.")
              scheduleOnce(EmbargoEnded(connectsLeft, embargo), backoff)
            }
          }
        }
      }

      override def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case EmbargoEnded(connectsLeft, nextEmbargo) =>
            log.debug("Reconnecting after backoff")
            connect(connectsLeft, nextEmbargo)
          case other => throw new IllegalArgumentException(s"Unexpected timer key $other") // compiler completeness check pleaser

        }
      }

      class Connected(
        requestOut: SubSourceOutlet[HttpRequest],
        responseIn: SubSinkInlet[HttpResponse]
      ) extends State {
        private var ongoingRequests: Map[AssociationTag, Map[AttributeKey[_], RequestResponseAssociation]] = Map.empty
        responseIn.pull()

        requestOut.setHandler(new OutHandler {
          override def onPull(): Unit =
            if (!isAvailable(requestIn)) pull(requestIn)
            else dispatchRequest(grab(requestIn))

          override def onDownstreamFinish(cause: Throwable): Unit = onDisconnected()
        })
        responseIn.setHandler(new InHandler {
          override def onPush(): Unit = {
            val response = responseIn.grab()
            val tag = response.attribute(associationTagKey).get
            require(ongoingRequests.contains(tag))
            ongoingRequests -= tag
            push(responseOut, response.removeAttribute(associationTagKey))
          }

          override def onUpstreamFinish(): Unit = onDisconnected()
          override def onUpstreamFailure(ex: Throwable): Unit = onDisconnected() // FIXME: log error
        })
        def onDisconnected(): Unit = {
          emitMultiple[HttpResponse](responseOut, ongoingRequests.values.map(errorResponse.withAttributes(_)).toVector, () => setHandler(responseOut, Unconnected))
          responseIn.cancel()
          requestOut.fail(new RuntimeException("connection broken"))

          if (isClosed(requestIn)) {
            // user closed PersistentConnection before and we were waiting for remaining responses
            completeStage()
          } else {
            // become(Unconnected) doesn't work because of using emit
            // so we need to do it more carefully here
            setHandler(requestIn, Unconnected)
            if (isAvailable(responseOut) && !hasBeenPulled(requestIn)) pull(requestIn)
          }
        }

        def dispatchRequest(req: HttpRequest): Unit = {
          val tag = new AssociationTag
          // Some cross-compilation woes here:
          // Explicit type ascription is needed to make both 2.12 and 2.13 compile.
          ongoingRequests = ongoingRequests.updated(tag, req.attributes.collect({
            case (key, value: RequestResponseAssociation) => key -> value
          }: PartialFunction[(AttributeKey[_], Any), (AttributeKey[_], RequestResponseAssociation)]))
          requestOut.push(req.addAttribute(associationTagKey, tag))
        }

        override def onPush(): Unit = dispatchRequest(grab(requestIn))
        override def onPull(): Unit = responseIn.pull()

        // onUpstreamFinish expects "reasonable behavior" from downstream stages, i.e. that
        // the downstream stage will eventually close all remaining inputs/outputs. Note
        // that the PersistentConnection is often used in combination with HTTP/2 connections
        // which to timeout if the stage completion stalls.
        override def onUpstreamFinish(): Unit = requestOut.complete()

        override def onUpstreamFailure(ex: Throwable): Unit = {
          requestOut.fail(ex)
          responseIn.cancel()
          failStage(ex)
        }
        override def onDownstreamFinish(cause: Throwable): Unit = {
          requestOut.complete()
          responseIn.cancel()
          super.onDownstreamFinish(cause)
        }
      }
    }
  }
}
