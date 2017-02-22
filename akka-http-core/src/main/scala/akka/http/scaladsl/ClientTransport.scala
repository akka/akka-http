/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.impl.engine.client.ProxyGraphStage
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.settings.{ ClientConnectionSettings }
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Tcp }
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Abstraction to allow the creation of alternative transports to run HTTP on.
 */
@ApiMayChange
trait ClientTransport { outer ⇒
  def connectTo(host: String, port: Int)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]]
}

object ClientTransport {
  def TCP(localAddress: Option[InetSocketAddress], settings: ClientConnectionSettings): ClientTransport =
    new TCPTransport(localAddress, settings)

  private case class TCPTransport(localAddress: Option[InetSocketAddress], settings: ClientConnectionSettings) extends ClientTransport {
    def connectTo(host: String, port: Int)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
      // The InetSocketAddress representing the remote address must be created unresolved because akka.io.TcpOutgoingConnection will
      // not attempt DNS resolution if the InetSocketAddress is already resolved. That behavior is problematic when it comes to
      // connection pools since it means that new connections opened by the pool in the future can end up using a stale IP address.
      // By passing an unresolved InetSocketAddress instead, we ensure that DNS resolution is performed for every new connection.
      Tcp().outgoingConnection(InetSocketAddress.createUnresolved(host, port), localAddress,
        settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
        .mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  }

  /**
   * Returns [[ClientTransport]] that performs HTTP CONNECT tunnelling which is useful when you want to use HTTPS proxy.
   *
   * Using this kind of [[ClientTransport]] means that after the tunnel between client and target host has been
   * established the subsequent communication is done on TCP level.
   *
   * To get know more about CONNECT tunnelling read https://tools.ietf.org/html/rfc7231#section-4.3.6
   */
  def proxy(localAddress: Option[InetSocketAddress], proxyAddress: InetSocketAddress, settings: ClientConnectionSettings): ClientTransport =
    new ProxyTransport(localAddress, proxyAddress, settings)

  private case class ProxyTransport(localAddress: Option[InetSocketAddress], proxyAddress: InetSocketAddress, settings: ClientConnectionSettings) extends ClientTransport {
    def connectTo(host: String, port: Int)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      val networkFlow = Tcp().outgoingConnection(proxyAddress, localAddress,
        settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
        .mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))

      val proxyBidiFlow = BidiFlow.fromGraph(new ProxyGraphStage(host, port, settings, system.log))

      proxyBidiFlow.joinMat(networkFlow)(Keep.right)
    }
  }
}