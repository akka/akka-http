/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.NotUsed
import akka.actor.Cancellable
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.model.HttpEntity
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi
private[http] object StreamUtils {

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   * Empty ByteStrings are discarded.
   */
  def byteStringTransformer(f: ByteString => ByteString, finish: () => ByteString): GraphStage[FlowShape[ByteString, ByteString]] = new SimpleLinearGraphStage[ByteString] {
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      override def onPush(): Unit = {
        val data = f(grab(in))
        if (data.nonEmpty) push(out, data)
        else pull(in)
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        val data = finish()
        if (data.nonEmpty) emit(out, data)
        completeStage()
      }

      setHandlers(in, out, this)
    }
  }

  def captureTermination[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Future[Unit]) = {
    val (newSource, termination, _, _) = captureMaterializationTerminationAndKillSwitch(source)
    (newSource, termination)
  }
  def captureMaterializationTerminationAndKillSwitch[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Future[Unit], Future[Unit], KillSwitch) = {
    val terminationPromise = Promise[Unit]()
    val materializationPromise = Promise[Unit]()
    val killResult = Promise[Unit]()
    val killSwitch = new KillSwitch {
      override def shutdown(): Unit = killResult.trySuccess(())
      override def abort(ex: Throwable): Unit = killResult.tryFailure(ex)
    }
    val transformer = new SimpleLinearGraphStage[T] {
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def preStart(): Unit = {
          materializationPromise.trySuccess(())
          killResult.future.value match {
            case Some(res) => handleKill(res)
            case None      => killResult.future.onComplete(killCallback.invoke)(ExecutionContexts.sameThreadExecutionContext)
          }
        }

        override def onPush(): Unit = push(out, grab(in))

        override def onPull(): Unit = pull(in)

        override def onUpstreamFailure(ex: Throwable): Unit = {
          terminationPromise.tryFailure(ex)
          failStage(ex)
        }

        override def postStop(): Unit = terminationPromise.trySuccess(())

        setHandlers(in, out, this)

        // KillSwitch implementation
        private[this] val killCallback = getAsyncCallback[Try[Unit]](handleKill)

        def handleKill(result: Try[Unit]): Unit = result match {
          case Success(_)  => completeStage()
          case Failure(ex) => failStage(ex)
        }
      }
    }
    (source.via(transformer), terminationPromise.future, materializationPromise.future, killSwitch)
  }

  def sliceBytesTransformer(start: Long, length: Long): Flow[ByteString, ByteString, NotUsed] = {
    val transformer = new SimpleLinearGraphStage[ByteString] {
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPull() = pull(in)

        var toSkip = start
        var remaining = length

        override def onPush(): Unit = {
          val element = grab(in)
          if (toSkip >= element.length)
            pull(in)
          else {
            val data = element.drop(toSkip.toInt).take(math.min(remaining, Int.MaxValue).toInt)
            remaining -= data.size
            push(out, data)
            if (remaining <= 0) completeStage()
          }

          if (toSkip > 0)
            toSkip -= element.length
        }

        setHandlers(in, out, this)
      }
    }
    Flow[ByteString].via(transformer).named("sliceBytes")
  }

  def limitByteChunksStage(maxBytesPerChunk: Int): GraphStage[FlowShape[ByteString, ByteString]] =
    new SimpleLinearGraphStage[ByteString] {
      override def initialAttributes = Attributes.name("limitByteChunksStage")

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

        var remaining = ByteString.empty

        def splitAndPush(data: ByteString): Unit = {
          val toPush = data.take(maxBytesPerChunk)
          val toKeep = data.drop(maxBytesPerChunk)
          push(out, toPush)
          remaining = toKeep
        }
        setHandlers(in, out, WaitingForData)

        case object WaitingForData extends InHandler with OutHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            if (elem.size <= maxBytesPerChunk) push(out, elem)
            else {
              splitAndPush(elem)
              setHandlers(in, out, DeliveringData)
            }
          }
          override def onPull(): Unit = pull(in)
        }

        case object DeliveringData extends InHandler() with OutHandler {
          var finishing = false
          override def onPush(): Unit = throw new IllegalStateException("Not expecting data")
          override def onPull(): Unit = {
            splitAndPush(remaining)
            if (remaining.isEmpty) {
              if (finishing) completeStage() else setHandlers(in, out, WaitingForData)
            }
          }
          override def onUpstreamFinish(): Unit = if (remaining.isEmpty) completeStage() else finishing = true
        }

        override def toString = "limitByteChunksStage"
      }
    }

  /**
   * Similar to Source.maybe but doesn't rely on materialization. Can only be used once.
   */
  trait OneTimeValve {
    def source[T]: Source[T, NotUsed]
    def open(): Unit
  }
  object OneTimeValve {
    def apply(): OneTimeValve = new OneTimeValve {
      val promise = Promise[Unit]()
      val _source = Source.fromFuture(promise.future).drop(1) // we are only interested in the completion event

      def source[T]: Source[T, NotUsed] = _source.asInstanceOf[Source[T, NotUsed]] // safe, because source won't generate any elements
      def open(): Unit = promise.success(())
    }
  }

  /**
   * Similar idea than [[FlowOps.statefulMapConcat]] but for a simple map.
   */
  def statefulMap[T, U](functionConstructor: () => T => U): Flow[T, U, NotUsed] =
    Flow[T].statefulMapConcat { () =>
      val f = functionConstructor()
      i => f(i) :: Nil
    }

  /**
   * Lifts the streams attributes into an element and passes them to the function for each passed through element.
   * Similar idea than [[FlowOps.statefulMapConcat]] but for a simple map.
   *
   * The result of `Attributes => (T => U)` is cached, and only the `T => U` function will be invoked afterwards for each element.
   */
  def statefulAttrsMap[T, U](functionConstructor: Attributes => T => U): Flow[T, U, NotUsed] =
    Flow[T].via(ExposeAttributes[T, U](functionConstructor))

  trait ScheduleSupport { self: GraphStageLogic =>
    /**
     * Schedule a block to be run once after the given duration in the context of this graph stage.
     */
    def scheduleOnce(delay: FiniteDuration)(block: => Unit): Cancellable =
      materializer.scheduleOnce(delay, new Runnable { def run() = runInContext(block) })

    def runInContext(block: => Unit): Unit = getAsyncCallback[AnyRef](_ => block).invoke(null)
  }

  private val EmptySource = Source.empty

  /** Dummy name to signify that the caller asserts that cancelSource is only run from within a GraphInterpreter context */
  val OnlyRunInGraphInterpreterContext: Materializer = null
  /**
   * Tries to guess whether a source needs to cancelled and how. In the best case no materialization should be needed.
   */
  def cancelSource(source: Source[_, _])(implicit materializer: Materializer): Unit = source match {
    case EmptySource => // nothing to do with empty source
    case x =>
      val mat =
        GraphInterpreter.currentInterpreterOrNull match {
          case null if materializer ne null => materializer
          case null                         => throw new IllegalStateException("Need to pass materializer to cancelSource if not run from GraphInterpreter context.")
          case x                            => x.subFusingMaterializer // try to use fuse if already running in interpreter context
        }
      x.runWith(Sink.ignore)(mat)
  }

  case class StreamControl(
    whenMaterialized: Future[Unit],
    whenTerminated:   Future[Unit],
    killSwitch:       Option[KillSwitch]
  )
  private val successfulDone = Future.successful(())
  object CaptureMaterializationAndTerminationOp extends EntityStreamOp[StreamControl] {
    val strictM: StreamControl = StreamControl(successfulDone, successfulDone, None)
    def apply[T, Mat](source: Source[T, Mat]): (Source[T, Mat], StreamControl) = {
      val (newSource, completion, materialization, killSwitch) =
        StreamUtils.captureMaterializationTerminationAndKillSwitch(source)

      (newSource, StreamControl(materialization, completion, Some(killSwitch)))
    }
  }
  object CaptureTerminationOp extends EntityStreamOp[Future[Unit]] {
    val strictM: Future[Unit] = successfulDone
    def apply[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Future[Unit]) = StreamUtils.captureTermination(source)
  }

  trait EntityStreamOp[M] {
    def strictM: M
    def apply[T, Mat](source: Source[T, Mat]): (Source[T, Mat], M)
  }

  def transformEntityStream[T <: HttpEntity, M](entity: T, streamOp: EntityStreamOp[M]): (T, M) =
    entity match {
      case x: HttpEntity.Strict => x.asInstanceOf[T] -> streamOp.strictM
      case x: HttpEntity.Default =>
        val (newData, whenCompleted) = streamOp(x.data)
        x.copy(data = newData).asInstanceOf[T] -> whenCompleted
      case x: HttpEntity.Chunked =>
        val (newChunks, whenCompleted) = streamOp(x.chunks)
        x.copy(chunks = newChunks).asInstanceOf[T] -> whenCompleted
      case x: HttpEntity.CloseDelimited =>
        val (newData, whenCompleted) = streamOp(x.data)
        x.copy(data = newData).asInstanceOf[T] -> whenCompleted
      case x: HttpEntity.IndefiniteLength =>
        val (newData, whenCompleted) = streamOp(x.data)
        x.copy(data = newData).asInstanceOf[T] -> whenCompleted
    }
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedByteStringSource[Mat](val byteStringStream: Source[ByteString, Mat]) extends AnyVal {
  def join(implicit materializer: Materializer): Future[ByteString] =
    byteStringStream.runFold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: Materializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
}

/** INTERNAL API */
@InternalApi private[http] case class ExposeAttributes[T, U](functionConstructor: Attributes => T => U)
  extends GraphStage[FlowShape[T, U]] {

  val in = Inlet[T]("ExposeAttributes.in")
  val out = Outlet[U]("ExposeAttributes.out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    val f = functionConstructor(inheritedAttributes)
    override def onPush(): Unit = push(out, f(grab(in)))
    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }
}
