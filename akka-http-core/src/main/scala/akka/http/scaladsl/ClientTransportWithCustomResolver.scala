/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.{ Flow, Tcp }
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future }

@ApiMayChange
trait ClientTransportWithCustomResolver extends ClientTransport {
  override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit unused: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] = {
    // delay hostname resolution until stream materialization
    Flow.setup { (mat, _) =>
      implicit val ec: ExecutionContext = mat.executionContext
      futureFlow {
        inetSocketAddress(host, port, settings).map { address =>
          Tcp()(mat.system).outgoingConnection(address, settings.localAddress,
            settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
            .mapMaterializedValue(_.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress)))
        }
      }
    }.mapMaterializedValue(_.flatten.flatten)
  }

  /**
   * Return an address to which the requests for this connection should be directed.
   * Called when a new connection is to be established.
   */
  protected def inetSocketAddress(host: String, port: Int, settings: ClientConnectionSettings)(implicit ec: ExecutionContext): Future[InetSocketAddress]

  private def futureFlow[I, O, M](flow: Future[Flow[I, O, M]]): Flow[I, O, Future[M]] =
    Flow.fromGraph(new akka.http.impl.forwardport.FutureFlow(flow))

}
