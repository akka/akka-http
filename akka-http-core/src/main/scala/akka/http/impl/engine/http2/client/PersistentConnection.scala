package akka.http.impl.engine.http2.client

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

/** INTERNAL API */
@InternalApi
private[http2] object PersistentConnection {
  def managedConnection(connectionFlow: Flow[HttpRequest, HttpResponse, Any]): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow.fromGraph(new Stage(connectionFlow))

  private class Stage(connectionFlow: Flow[HttpRequest, HttpResponse, Any]) extends GraphStage[FlowShape[HttpRequest, HttpResponse]] {
    val requestIn = Inlet[HttpRequest]("PersistentConnection.requestIn")
    val responseOut = Outlet[HttpResponse]("PersistentConnection.responseOut")

    val shape: FlowShape[HttpRequest, HttpResponse] = FlowShape(requestIn, responseOut)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    }
  }
}
