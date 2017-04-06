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

    //    new Exception(s"HERE: ${settings.idleTimeout}").printStackTrace()
    //
    //    println(s" bbb settings = ${settings}")
    //    println(s" bbb settings.idleTimeout = ${settings.idleTimeout}")

    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      val s = settings.asInstanceOf[akka.http.scaladsl.settings.ClientConnectionSettings]
      // The InetSocketAddress representing the remote address must be created unresolved because akka.io.TcpOutgoingConnection will
      // not attempt DNS resolution if the InetSocketAddress is already resolved. That behavior is problematic when it comes to
      // connection pools since it means that new connections opened by the pool in the future can end up using a stale IP address.
      // By passing an unresolved InetSocketAddress instead, we ensure that DNS resolution is performed for every new connection.
      println(s"  BBB ClientConnectionSettings: settings.idleTimeout = ${s.idleTimeout}")

      val uri = InetSocketAddress.createUnresolved(host, port)
      Tcp().outgoingConnection(uri, localAddress,
        s.socketOptions, halfClose = true, s.connectingTimeout, s.idleTimeout) // FIXME does this work?
        .mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
    }
  }
}
