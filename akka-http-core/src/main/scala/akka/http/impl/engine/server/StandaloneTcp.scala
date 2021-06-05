package akka.http.impl.engine.server

import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

import akka.Done
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.io.BufferPool
import akka.io.Inet
import akka.io.Inet.SocketOption
import SelectionHandler.ChannelAcceptable
import SelectionHandler.ChannelReadable
import akka.io.Tcp
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object StandaloneTcp {
  def bind(
    interface:   String,
    port:        Int,
    backlog:     Int                                 = 100,
    options:     immutable.Traversable[SocketOption] = Nil,
    halfClose:   Boolean                             = false,
    idleTimeout: Duration                            = Duration.Inf): Source[IncomingConnection, Future[ServerBinding]] =
    Source.fromGraph(new ConnectionSourceStage(
      new InetSocketAddress(interface, port),
      backlog,
      options,
      halfClose,
      idleTimeout))

  class ConnectionSourceStage(
    address:   InetSocketAddress,
    backlog:   Int,
    options:   Traversable[Inet.SocketOption],
    halfClose: Boolean,
    idleTime:  Duration) extends GraphStageWithMaterializedValue[SourceShape[IncomingConnection], Future[ServerBinding]] {
    val connectionOut = Outlet[IncomingConnection]("ConnectionSourceStage.connectionOut")
    def shape: SourceShape[IncomingConnection] = SourceShape(connectionOut)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[ServerBinding]) = {
      val serverBindingPromise = Promise[ServerBinding]()

      val stage = new GraphStageLogic(shape) with OutHandler {
        val channel = ServerSocketChannel.open
        channel.configureBlocking(false)

        private[this] var log: LoggingAdapter = _
        private[this] var localAddress: InetSocketAddress = _
        private[this] var channelRegistry: ChannelRegistry = _
        private[this] var registration: ChannelRegistration = _

        override def preStart(): Unit = {
          val system = materializer.asInstanceOf[ActorMaterializer].system
          log = Logging(system, classOf[ConnectionSourceStage])
          channelRegistry = ChanReg(system).registry
          bindChannel()
        }

        def handleRegistryEvents(evt: (ActorRef, Any)): Unit = {
          evt._2 match {
            case registration: ChannelRegistration =>
              this.registration = registration
              localAddress = channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
              if (isAvailable(connectionOut)) tryAccepting()

              // FIXME: fix unbinding calls
              println(s"Bound to $localAddress")
              serverBindingPromise.trySuccess(new ServerBinding(localAddress)(() => Future.successful(()), Future.successful(Done)))
            case ChannelAcceptable =>
              dispatchNewChannel(channel.accept())
          }
        }

        setHandler(connectionOut, this)

        override def onPull(): Unit = tryAccepting()
        // FIXME: implement cancellation for unbinding
        private def tryAccepting(): Unit = {
          // optimistically try to accept new connection
          channel.accept() match {
            case null =>
              // no channel available currently (default case) -> defer to selection handler and wait for notification
              if (registration ne null) registration.enableInterest(SelectionKey.OP_ACCEPT)
            case ch =>
              dispatchNewChannel(ch)
          }
        }

        private def dispatchNewChannel(ch: SocketChannel): Unit = {
          // FIXME: create flow and push to user
          ch.configureBlocking(false)
          try ch.socket.setTcpNoDelay(true) catch {
            case e: SocketException =>
              // as reported in #16653 some versions of netcat (`nc -z`) doesn't allow setTcpNoDelay
              // continue anyway
              log.debug("Could not enable TcpNoDelay: {}", e.getMessage)
          }
          options.foreach(_.afterConnect(ch.socket))

          push(
            connectionOut,
            IncomingConnection(
              ch.getLocalAddress.asInstanceOf[InetSocketAddress],
              ch.getRemoteAddress.asInstanceOf[InetSocketAddress],
              Flow.fromGraph(new TcpConnectionStage(ch, channelRegistry))
            ))
        }

        private def bindChannel(): Unit =
          localAddress =
            try {
              val socket = channel.socket
              options.foreach(_.beforeServerSocketBind(socket))
              socket.bind(address, backlog)
              val ret = socket.getLocalSocketAddress match {
                case isa: InetSocketAddress => isa
                case x                      => throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
              }
              channelRegistry.register(channel, 0)(getStageActor(handleRegistryEvents).ref)
              log.debug("Successfully bound to {}", ret)
              options.foreach {
                case o: Inet.SocketOptionV2 => o.afterBind(channel.socket)
                case _                      =>
              }
              ret
            } catch {
              case NonFatal(e) =>
                log.error(e, "Bind failed for TCP channel on endpoint [{}]", localAddress)
                serverBindingPromise.tryFailure(e)
                throw e
            }
      }

      (stage, serverBindingPromise.future)
    }
  }

  class TcpConnectionStage(channel: SocketChannel, channelRegistry: ChannelRegistry) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val userIn = Inlet[ByteString]("TcpConnectionStage.userIn")
    val userOut = Outlet[ByteString]("TcpConnectionStage.userOut")

    val shape: FlowShape[ByteString, ByteString] = FlowShape(userIn, userOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        var bufferPool: BufferPool = _
        var registration: ChannelRegistration = _

        setHandlers(userIn, userOut, this)

        override def preStart(): Unit = {
          val system = materializer.asInstanceOf[ActorMaterializer].system
          this.bufferPool = Tcp(system).bufferPool
          channelRegistry.register(channel, 0)(getStageActor(handleRegistryEvents).ref)
        }

        def handleRegistryEvents(evt: (ActorRef, Any)): Unit = evt._2 match {
          case reg: ChannelRegistration =>
            // FIXME: do we need an extra state before channel registration? Or can we put that into the listener stage?
            this.registration = reg
            pull(userIn)

          case ChannelReadable =>
            tryRead()
        }

        override def onPush(): Unit = {
          // FIXME: need internal buffer because we might need to buffer here for several reasons

          val data = grab(userIn)
          val buffer = bufferPool.acquire()
          try {
            buffer.clear()
            require(data.size <= buffer.limit())
            data.copyToBuffer(buffer)
            buffer.flip()
            val written = channel.write(buffer)
            require(written == data.size) // FIXME: for now expect that all data be written all the time
            pull(userIn)
          } finally bufferPool.release(buffer)
        }
        override def onPull(): Unit = tryRead()

        private def tryRead(): Unit = {
          require(isAvailable(userOut))
          val buffer = bufferPool.acquire()
          try {
            buffer.clear()
            val result = channel.read(buffer)
            buffer.flip()
            // FIXME: should we read multiple batches in one go and limit by setting (> pool buffer size) or maybe
            //        that's just useless complexity and you should increase direct buffer size in the first place?

            result match {
              case 0 =>
                registration.enableInterest(SelectionKey.OP_READ)
              case -1 =>
                registration.cancelAndClose(() => ())
                completeStage()
              case _ =>
                push(userOut, ByteString.fromByteBuffer(buffer))
            }
          } finally bufferPool.release(buffer)
        }
        // FIXME: handle completion conditions
      }
  }
}
