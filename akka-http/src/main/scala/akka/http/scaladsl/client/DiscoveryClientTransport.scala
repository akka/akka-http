/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import java.net.InetSocketAddress

import akka.annotation.ApiMayChange
import akka.discovery.ServiceDiscovery
import akka.http.scaladsl.ClientTransportWithCustomResolver
import akka.http.scaladsl.settings.ClientConnectionSettings

import scala.concurrent.{ ExecutionContext, Future }

@ApiMayChange
class DiscoveryClientTransport(discovery: ServiceDiscovery) extends ClientTransportWithCustomResolver {

  protected def inetSocketAddress(host: String, port: Int, settings: ClientConnectionSettings)(implicit ec: ExecutionContext): Future[InetSocketAddress] = {
    discovery.lookup(host, settings.connectingTimeout).map { resolved =>
      resolved.addresses match {
        case Seq() =>
          throw new IllegalStateException(s"No addresses looking up [$host]")
        case addresses =>
          // TODO take an arbitrary address? Or use some strategy?
          val address = addresses.head
          address.address match {
            case Some(inetAddress) => new InetSocketAddress(inetAddress, address.port.getOrElse(port))
            case None =>
              // fall back to the JDK DNS resolver
              InetSocketAddress.createUnresolved(address.host, address.port.getOrElse(port))
          }
      }
    }
  }
}
