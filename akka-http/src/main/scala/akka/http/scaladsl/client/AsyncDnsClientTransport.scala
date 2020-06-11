/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import java.net.{ InetAddress, InetSocketAddress }

import akka.actor.{ ActorRef, ActorSystem }
import akka.annotation.ApiMayChange

import scala.collection.immutable
//import akka.discovery.ServiceDiscovery.{DiscoveryTimeoutException, Resolved, ResolvedTarget}
//import akka.discovery.{Lookup, ServiceDiscovery}
import akka.dispatch.MessageDispatcher
import akka.event.Logging
import akka.http.scaladsl.ClientTransportWithCustomResolver
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.io.{ Dns, IO, SimpleDnsCache }
import akka.io.dns.{ AAAARecord, ARecord, DnsProtocol }
import akka.io.dns.DnsProtocol.{ Ip, Srv }
import akka.io.dns.internal.AsyncDnsManager
import akka.pattern.AskTimeoutException
import akka.util.{ OptionVal, Timeout }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import akka.pattern.ask
import scala.concurrent.duration._

@ApiMayChange
class AsyncDnsClientTransport()(implicit system: ActorSystem) extends ClientTransportWithCustomResolver {

  protected def inetSocketAddress(host: String, port: Int, settings: ClientConnectionSettings)(implicit ec: ExecutionContext): Future[InetSocketAddress] = {
    lookupIp(host, settings.connectingTimeout).map {
      case Seq() =>
        throw new IllegalStateException(s"No addresses looking up [$host]")
      case addresses =>
        // TODO take an arbitrary address? Or use some strategy?
        val inetAddress = addresses.head
        new InetSocketAddress(inetAddress, port)
    }
  }

  private val log = Logging(system, getClass)
  private implicit val ec: ExecutionContext = system.dispatcher
  private val dns = initializeDns()

  // exposed for testing
  private def initializeDns(): ActorRef = {
    if (system.settings.config.getString("akka.io.dns.resolver") == "async-dns") {
      log.debug("using system resolver as it is set to async-dns")
      IO(Dns)(system)
    } else {
      log.debug("system resolver is not async-dns. Loading isolated resolver")
      Dns(system).loadAsyncDns("SD-DNS")
    }
  }

  // updated from ask AsyncDnsManager.GetCache, but doesn't have to volatile since will still work when unset
  // (eventually visible)
  private var asyncDnsCache: OptionVal[SimpleDnsCache] = OptionVal.None

  dns.ask(AsyncDnsManager.GetCache)(Timeout(30.seconds)).onComplete {
    case Success(cache: SimpleDnsCache) =>
      asyncDnsCache = OptionVal.Some(cache)
    case Success(other) =>
      log.error("Expected AsyncDnsCache but got [{}]", other.getClass.getName)
    case Failure(e) =>
      log.error(e, "Couldn't retrieve DNS cache: {}")
  }

  private def convertToTimeout(timeout: FiniteDuration): PartialFunction[Throwable, Future[immutable.Seq[InetAddress]]] = {
    case _: AskTimeoutException =>
      import akka.util.PrettyDuration._
      Future.failed(new AskTimeoutException(s"Dns resolve did not respond within ${timeout.pretty}"))
  }

  private def lookupIp(lookup: String, resolveTimeout: FiniteDuration) = {
    log.debug("Lookup[{}] translated to A/AAAA lookup as does not have portName and protocol", lookup)
    val mode = Ip()

    def ipRecordsToResolved(resolved: DnsProtocol.Resolved): immutable.Seq[InetAddress] = {
      val addresses = resolved.records.collect {
        case a: ARecord    => a.ip
        case a: AAAARecord => a.ip
      }
      addresses
    }

    def legacyDnsToResolved(resolved: Dns.Resolved): immutable.Seq[InetAddress] = {
      resolved.ipv4 ++ resolved.ipv6
    }

    def askResolve(): Future[immutable.Seq[InetAddress]] = {
      dns
        .ask(DnsProtocol.Resolve(lookup, mode))(resolveTimeout)
        .map {
          case resolved: DnsProtocol.Resolved =>
            log.debug("{} lookup result: {}", mode, resolved)
            ipRecordsToResolved(resolved)
          case resolved =>
            log.warning("Resolved UNEXPECTED (resolving to Nil): {}", resolved.getClass)
            Nil
        }
        .recoverWith(convertToTimeout(resolveTimeout))
    }

    asyncDnsCache match {
      case OptionVal.Some(cache) =>
        cache.cached(lookup) match {
          case Some(resolved) =>
            log.debug("{} lookup cached: {}", mode, resolved)
            Future.successful(legacyDnsToResolved(resolved))
          case None =>
            askResolve()
        }
      case OptionVal.None =>
        askResolve()

    }

  }

}
