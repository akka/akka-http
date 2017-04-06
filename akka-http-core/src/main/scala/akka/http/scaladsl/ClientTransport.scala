/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.{ Flow, Tcp }
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Abstraction to allow the creation of alternative transports to run HTTP on.
 */
@ApiMayChange
trait ClientTransport {
  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]]
}

object ClientTransport {
  def TCP(localAddress: Option[InetSocketAddress]): ClientTransport = // , settings: ClientConnectionSettings): ClientTransport =
    new TCPTransport(localAddress) // , settings)

  /** INTERNAL API */
  @InternalApi private[akka] case class TCPTransport(localAddress: Option[InetSocketAddress])
    extends ClientTransport {

    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      val s = settings.asInstanceOf[akka.http.scaladsl.settings.ClientConnectionSettings]
      Tcp().outgoingConnection(InetSocketAddress.createUnresolved(host, port), localAddress,
        s.socketOptions, halfClose = true, s.connectingTimeout, s.idleTimeout)
        .mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
    }
  }
}
