/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.client

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.model.{ AttributeKey, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCodes }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

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
  def managedConnection(connectionFlow: Flow[HttpRequest, HttpResponse, Any]): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow.fromGraph(new Stage(connectionFlow))

  private class AssociationTag extends RequestResponseAssociation
  private val associationTagKey = AttributeKey[AssociationTag]("PersistentConnection.associationTagKey")
  private val errorResponse =
    HttpResponse(
      StatusCodes.BadGateway,
      entity = "The server closed the connection before delivering a response.")

  private class Stage(connectionFlow: Flow[HttpRequest, HttpResponse, Any]) extends GraphStage[FlowShape[HttpRequest, HttpResponse]] {
    val requestIn = Inlet[HttpRequest]("PersistentConnection.requestIn")
    val responseOut = Outlet[HttpResponse]("PersistentConnection.responseOut")

    val shape: FlowShape[HttpRequest, HttpResponse] = FlowShape(requestIn, responseOut)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {
      become(Unconnected)

      def become(state: State): Unit = setHandlers(requestIn, responseOut, state)

      trait State extends InHandler with OutHandler
      object Unconnected extends State {
        override def onPush(): Unit = connect()
        override def onPull(): Unit =
          if (!hasBeenPulled(requestIn)) // requestIn might already have been pulled when we failed and went back to Unconnected
            pull(requestIn)

      }

      def connect(): Unit = {
        val requestOut = new SubSourceOutlet[HttpRequest]("PersistentConnection.requestOut")
        val responseIn = new SubSinkInlet[HttpResponse]("PersistentConnection.responseIn")

        Source.fromGraph(requestOut.source)
          .via(connectionFlow)
          .runWith(responseIn.sink)(subFusingMaterializer)

        become(new Connected(requestOut, responseIn))
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
          ongoingRequests += (tag -> req.attributes.collect {
            case (key, value: RequestResponseAssociation) => key -> value
          })
          requestOut.push(req.addAttribute(associationTagKey, tag))
        }

        override def onPush(): Unit = dispatchRequest(grab(requestIn))
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
