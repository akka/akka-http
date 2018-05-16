package akka.http.impl.engine.client.pool

import java.net.InetSocketAddress

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolFlow
import akka.http.impl.engine.client.PoolFlow.RequestContext
import akka.http.impl.engine.client.pool.SlotState._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, headers}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.testkit.AkkaSpec

import scala.concurrent.{Future, Promise}
import scala.util.Try

class SlotStateSpec extends AkkaSpec {
  "The new connection pool slot state machine" should {
    "successfully complete a 'happy path' request" in {
      var state: SlotState = Unconnected
      val responsePromise = Promise[HttpResponse]
      val outgoingConnection = Http.OutgoingConnection(
        InetSocketAddress.createUnresolved("127.0.0.1", 1234),
        InetSocketAddress.createUnresolved("127.0.0.1", 5678))

      val context = new MockSlotContext(system.log)
      state = context.expectOpenConnection {
        state.onPreConnect(context)
      }
      state = state.onNewRequest(context, RequestContext(HttpRequest(), responsePromise, 0))

      state = state.onConnectionAttemptSucceeded(context, outgoingConnection)

      context.expectRequestDispatchToConnection()

      state = state.onResponseReceived(context, HttpResponse())
      state = state.onResponseDispatchable(context)
      state = state.onResponseEntitySubscribed(context)
      state = state.onResponseEntityCompleted(context)
      state should be(Idle)

      state = state.onConnectionCompleted(context)
      state should be(Unconnected)
    }
  }

  class MockSlotContext(log: LoggingAdapter, val settings: ConnectionPoolSettings = ConnectionPoolSettings("")) extends SlotContext {


    var connectionClosed = true
    var connectionOpenRequested = false
    var pushedRequest: Option[HttpRequest] = None
    var dispatchedResponse: Option[Try[HttpResponse]] = None

    override def openConnection(): Unit = {
      connectionOpenRequested should be(false)
      connectionOpenRequested = true
    }

    override def isConnectionClosed: Boolean = connectionClosed

    override def pushRequestToConnection(request: HttpRequest): Unit =
      pushedRequest = Some(request)

    override def dispatchResponseResult(req: PoolFlow.RequestContext, result: Try[HttpResponse]): Unit =
      dispatchedResponse = Some(result)

    override def willCloseAfter(response: HttpResponse): Boolean =
      response.header[headers.Connection].exists(_.hasClose)

    override def debug(message: String): Unit =
      log.debug(message)

    override def debug(message: String, arg1: AnyRef): Unit =
      log.debug(message, arg1)

    override def debug(message: String, arg1: AnyRef, arg2: AnyRef): Unit =
      log.debug(message, arg1, arg2)

    override def debug(message: String, arg1: AnyRef, arg2: AnyRef, arg3: AnyRef): Unit =
      log.debug(message, arg1, arg2, arg3)

    override def warning(message: String): Unit =
      log.warning(message)

    override def warning(message: String, arg1: AnyRef): Unit =
      log.warning(message, arg1)

    def expectOpenConnection[T](cb: => T) = {
      connectionClosed should be(true)
      connectionOpenRequested should be(false)

      val res = cb
      connectionOpenRequested should be(true)

      connectionOpenRequested = false
      connectionClosed = false
      res
    }

    def expectRequestDispatchToConnection() = {
      val request = pushedRequest.get
      pushedRequest = None
      request
    }

  }
}

