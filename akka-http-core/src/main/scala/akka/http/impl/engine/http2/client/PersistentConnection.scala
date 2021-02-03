/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.client

import akka.NotUsed
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.{ AttributeKey, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCodes }
import akka.stream.scaladsl.{ Flow, Keep, Source }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

/** INTERNAL API */
@InternalApi
private[http2] object PersistentConnection {
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
  def managedConnection(connectionFlow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], maxAttempts: Int): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow.fromGraph(new Stage(connectionFlow, maxAttempts match {
      case 0 => None
      case n => Some(n)
    }))

  private class AssociationTag extends RequestResponseAssociation
  private val associationTagKey = AttributeKey[AssociationTag]("PersistentConnection.associationTagKey")
  private val errorResponse =
    HttpResponse(
      StatusCodes.BadGateway,
      entity = "The server closed the connection before delivering a response.")

  private class Stage(connectionFlow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], maxAttempts: Option[Int]) extends GraphStage[FlowShape[HttpRequest, HttpResponse]] {
    val requestIn = Inlet[HttpRequest]("PersistentConnection.requestIn")
    val responseOut = Outlet[HttpResponse]("PersistentConnection.responseOut")

    val shape: FlowShape[HttpRequest, HttpResponse] = FlowShape(requestIn, responseOut)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {
      become(Unconnected)

      def become(state: State): Unit = setHandlers(requestIn, responseOut, state)

      trait State extends InHandler with OutHandler
      object Unconnected extends State {
        override def onPush(): Unit = connect(maxAttempts)
        override def onPull(): Unit =
          if (!isAvailable(requestIn) && !hasBeenPulled(requestIn)) // requestIn might already have been pulled when we failed and went back to Unconnected
            pull(requestIn)
      }

      def connect(connectsLeft: Option[Int]): Unit = {
        val requestOut = new SubSourceOutlet[HttpRequest]("PersistentConnection.requestOut")
        val responseIn = new SubSinkInlet[HttpResponse]("PersistentConnection.responseIn")
        val connection = Promise[OutgoingConnection]()

        become(new Connecting(connection.future, requestOut, responseIn, connectsLeft.map(_ - 1)))

        connection.completeWith(Source.fromGraph(requestOut.source)
          .viaMat(connectionFlow)(Keep.right)
          .toMat(responseIn.sink)(Keep.left)
          .run()(subFusingMaterializer))
      }

      class Connecting(
        connected:    Future[OutgoingConnection],
        requestOut:   SubSourceOutlet[HttpRequest],
        responseIn:   SubSinkInlet[HttpResponse],
        connectsLeft: Option[Int]
      ) extends State {
        connected.onComplete({
          case Success(_) =>
            onConnected.invoke(())
          case Failure(cause) =>
            onFailed.invoke(cause)
        })(ExecutionContexts.parasitic)

        var requestOutPulled = false
        requestOut.setHandler(new OutHandler {
          override def onPull(): Unit =
            requestOutPulled = true
          override def onDownstreamFinish(): Unit = ()
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
          responseIn.cancel()
          requestOut.fail(new RuntimeException("connection broken", cause))

          if (connectsLeft.contains(0)) {
            failStage(new RuntimeException(s"Connection failed after $maxAttempts attempts", cause))
          } else {
            setHandler(requestIn, Unconnected)
            log.info(s"Connection attempt failed: ${cause.getMessage}. Trying to connect again${connectsLeft.map(n => s" ($n attempts left)").getOrElse("")}.")
            connect(connectsLeft)
          }
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

          override def onDownstreamFinish(): Unit = onDisconnected()
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
          // become(Unconnected) doesn't work because of using emit
          // so we need to do it more carefully here
          setHandler(requestIn, Unconnected)
          if (isAvailable(responseOut) && !hasBeenPulled(requestIn)) pull(requestIn)
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

        override def onPush(): Unit = {
          dispatchRequest(grab(requestIn))
        }
        override def onPull(): Unit = responseIn.pull()

        override def onUpstreamFinish(): Unit = {
          requestOut.complete()
          responseIn.cancel()
          completeStage()
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          requestOut.fail(ex)
          responseIn.cancel()
          failStage(ex)
        }
        override def onDownstreamFinish(): Unit = {
          requestOut.complete()
          responseIn.cancel()
          super.onDownstreamFinish()
        }
      }
    }
  }
}
