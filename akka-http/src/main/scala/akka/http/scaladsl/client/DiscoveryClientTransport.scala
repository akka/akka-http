/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.discovery.ServiceDiscovery
import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.{ClientTransport, Http}
import akka.stream.scaladsl.{Flow, Keep, Source, Tcp}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import akka.stream._
import akka.util.{ByteString, OptionVal}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

@ApiMayChange
class DiscoveryClientTransport(discovery: ServiceDiscovery) extends ClientTransport {
  override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] = {
    // The current implementation invokes service discovery for every connection. That might be heavy for
    // some discovery mechanisms. We might want to eventually move service discovery out of the 'hot path'
    // with a more push-based API for refreshing discovery information, see
    // https://github.com/akka/akka/issues/28931
    implicit val ec = system.dispatcher
    futureFlow(
      discovery.lookup(host, settings.connectingTimeout).map { resolved =>
        resolved.addresses match {
          case Seq() =>
            throw new IllegalStateException(s"No addresses looking up [$host]")
          case addresses =>
            // TODO take an arbitrary address? Or use some strategy?
            val address = addresses(0)
            address.address match {
              case Some(inetAddress) =>
                Tcp().outgoingConnection(new InetSocketAddress(inetAddress, address.port.getOrElse(port)), settings.localAddress,
                  settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
                  .mapMaterializedValue(_.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
              case None =>
                Tcp().outgoingConnection(InetSocketAddress.createUnresolved(address.host, address.port.getOrElse(port)), settings.localAddress,
                  settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
                  .mapMaterializedValue(_.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
            }
        }
      }
    ).mapMaterializedValue(_.flatten)

  }

  private def futureFlow[I, O, M](flow: Future[Flow[I, O, M]]): Flow[I, O, Future[M]] =
    Flow.fromGraph(new FutureFlow(flow))

  // TODO #3069 remove this and use the upstream value instead
  private class FutureFlow[In, Out, M](futureFlow: Future[Flow[In, Out, M]])
    extends GraphStageWithMaterializedValue[FlowShape[In, Out], Future[M]] {
    val in = Inlet[In](s"${this}.in")
    val out = Outlet[Out](s"${this}.out")
    override val shape: FlowShape[In, Out] = FlowShape(in, out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
      val innerMatValue = Promise[M]
      val logic = new GraphStageLogic(shape) {

        //seems like we must set handlers BEFORE preStart
        setHandlers(in, out, Initializing)

        override def preStart(): Unit = {
          futureFlow.value match {
            case Some(tryFlow) =>
              Initializing.onFuture(tryFlow)
            case None =>
              val cb = getAsyncCallback(Initializing.onFuture)
              futureFlow.onComplete(cb.invoke)(ExecutionContexts.parasitic)
              //in case both ports are closed before future completion
              setKeepGoing(true)
          }
        }

        override def postStop(): Unit = {
          if (!innerMatValue.isCompleted) {
            innerMatValue.failure(new AbruptStageTerminationException(this))
          }
        }

        object Initializing extends InHandler with OutHandler {
          // we don't expect a push since we bever pull upstream during initialization
          override def onPush(): Unit = throw new IllegalStateException("unexpected push during initialization")

          var upstreamFailure = OptionVal.none[Throwable]

          override def onUpstreamFailure(ex: Throwable): Unit = {
            upstreamFailure = OptionVal.Some(ex)
          }

          //will later be propagated to the materialized flow (by examining isClosed(in))
          override def onUpstreamFinish(): Unit = {}

          //will later be propagated to the materialized flow (by examining isAvailable(out))
          override def onPull(): Unit = {}

          var downstreamFinished: Boolean = false
          override def onDownstreamFinish(): Unit = {
            downstreamFinished = true
          }

          def onFuture(futureRes: Try[Flow[In, Out, M]]) = futureRes match {
            case Failure(exception) =>
              setKeepGoing(false)
              innerMatValue.failure(new IllegalStateException("Never materialized", exception))
              failStage(exception)
            case Success(flow) =>
              //materialize flow, connect inlet and outlet, feed with potential events and set handlers
              connect(flow)
              setKeepGoing(false)
          }
        }

        def connect(flow: Flow[In, Out, M]): Unit = {
          val subSource = new SubSourceOutlet[In]("FutureFlow.subIn")
          val subSink = new SubSinkInlet[Out]("FutureFlow.subOut")

          subSource.setHandler {
            new OutHandler {
              override def onPull(): Unit = if (!isClosed(in)) tryPull(in)

              override def onDownstreamFinish(): Unit = if (!isClosed(in)) cancel(in)
            }
          }
          subSink.setHandler {
            new InHandler {
              override def onPush(): Unit = push(out, subSink.grab())

              override def onUpstreamFinish(): Unit = complete(out)

              override def onUpstreamFailure(ex: Throwable): Unit = fail(out, ex)
            }
          }
          Try {
            Source.fromGraph(subSource.source).viaMat(flow)(Keep.right).to(subSink.sink).run()(subFusingMaterializer)
          } match {
            case Success(matVal) =>
              innerMatValue.success(matVal)
              Initializing.upstreamFailure match {
                case OptionVal.Some(ex) =>
                  subSource.fail(ex)
                case OptionVal.None =>
                  if (isClosed(in))
                    subSource.complete()
              }
              if (Initializing.downstreamFinished) subSink.cancel()
              else {
                  //todo: should this be invoked before and independently of checking downstreamCause?
                  // in most case if downstream pulls and then closes, the pull is 'lost'. is it possible for some flows to actually care about this? (non-eager broadcast?)
                  if (isAvailable(out)) {
                    subSink.pull()
                  }
              }
            case Failure(ex) =>
              innerMatValue.failure(new IllegalStateException("Never materialized", ex))
              failStage(ex)
          }
          setHandlers(in, out, new InHandler with OutHandler {
            override def onPull(): Unit = subSink.pull()

            override def onDownstreamFinish(): Unit = subSink.cancel()

            override def onPush(): Unit = subSource.push(grab(in))

            override def onUpstreamFinish(): Unit = subSource.complete()

            override def onUpstreamFailure(ex: Throwable): Unit = subSource.fail(ex)
          })
        }
      }
      (logic, innerMatValue.future)
    }
  }
}
