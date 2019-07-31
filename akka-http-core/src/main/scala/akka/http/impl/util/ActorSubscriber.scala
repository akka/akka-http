/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.impl.util

import java.util.concurrent.ConcurrentHashMap

import org.reactivestreams.{ Subscriber, Subscription }
import akka.actor._
import akka.annotation.InternalApi

import scala.util.control.NonFatal

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] object ReactiveStreamsCompliance {

  final val CanNotSubscribeTheSameSubscriberMultipleTimes =
    "can not subscribe the same subscriber multiple times (see reactive-streams specification, rules 1.10 and 2.12)"

  final val SupportsOnlyASingleSubscriber =
    "only supports one subscriber (which is allowed, see reactive-streams specification, rule 1.12)"

  final val NumberOfElementsInRequestMustBePositiveMsg =
    "The number of requested elements must be > 0 (see reactive-streams specification, rule 3.9)"

  final val SubscriberMustNotBeNullMsg = "Subscriber must not be null, rule 1.9"

  final val ExceptionMustNotBeNullMsg = "Exception must not be null, rule 2.13"

  final val ElementMustNotBeNullMsg = "Element must not be null, rule 2.13"

  final val SubscriptionMustNotBeNullMsg = "Subscription must not be null, rule 2.13"

  final def numberOfElementsInRequestMustBePositiveException: Throwable =
    new IllegalArgumentException(NumberOfElementsInRequestMustBePositiveMsg)

  final def canNotSubscribeTheSameSubscriberMultipleTimesException: Throwable =
    new IllegalStateException(CanNotSubscribeTheSameSubscriberMultipleTimes)

  final def subscriberMustNotBeNullException: Throwable =
    new NullPointerException(SubscriberMustNotBeNullMsg)

  final def exceptionMustNotBeNullException: Throwable =
    new NullPointerException(ExceptionMustNotBeNullMsg)

  final def elementMustNotBeNullException: Throwable =
    new NullPointerException(ElementMustNotBeNullMsg)

  final def subscriptionMustNotBeNullException: Throwable =
    new NullPointerException(SubscriptionMustNotBeNullMsg)

  final def rejectDuplicateSubscriber[T](subscriber: Subscriber[T]): Unit = {
    // since it is already subscribed it has received the subscription first
    // and we can emit onError immediately
    tryOnError(subscriber, canNotSubscribeTheSameSubscriberMultipleTimesException)
  }

  final def rejectAdditionalSubscriber[T](subscriber: Subscriber[T], rejector: String): Unit = {
    tryOnSubscribe(subscriber, CancelledSubscription)
    tryOnError(subscriber, new IllegalStateException(s"$rejector $SupportsOnlyASingleSubscriber"))
  }

  final def rejectDueToNonPositiveDemand[T](subscriber: Subscriber[T]): Unit =
    tryOnError(subscriber, numberOfElementsInRequestMustBePositiveException)

  final def requireNonNullSubscriber[T](subscriber: Subscriber[T]): Unit =
    if (subscriber eq null) throw subscriberMustNotBeNullException

  final def requireNonNullException(cause: Throwable): Unit =
    if (cause eq null) throw exceptionMustNotBeNullException

  final def requireNonNullElement[T](element: T): Unit =
    if (element == null) throw elementMustNotBeNullException

  final def requireNonNullSubscription(subscription: Subscription): Unit =
    if (subscription == null) throw subscriptionMustNotBeNullException

  sealed trait SpecViolation extends Throwable

  @SerialVersionUID(1L)
  final class SignalThrewException(message: String, cause: Throwable)
    extends IllegalStateException(message, cause)
    with SpecViolation

  final def tryOnError[T](subscriber: Subscriber[T], error: Throwable): Unit =
    error match {
      case sv: SpecViolation =>
        throw new IllegalStateException("It is not legal to try to signal onError with a SpecViolation", sv)
      case other =>
        try subscriber.onError(other)
        catch {
          case NonFatal(t) => throw new SignalThrewException(s"${subscriber}.onError", t)
        }
    }

  final def tryOnNext[T](subscriber: Subscriber[T], element: T): Unit = {
    requireNonNullElement(element)
    try subscriber.onNext(element)
    catch {
      case NonFatal(t) => throw new SignalThrewException(s"${subscriber}.onNext", t)
    }
  }

  final def tryOnSubscribe[T](subscriber: Subscriber[T], subscription: Subscription): Unit = {
    try subscriber.onSubscribe(subscription)
    catch {
      case NonFatal(t) => throw new SignalThrewException(s"${subscriber}.onSubscribe", t)
    }
  }

  final def tryOnComplete[T](subscriber: Subscriber[T]): Unit = {
    try subscriber.onComplete()
    catch {
      case NonFatal(t) => throw new SignalThrewException(s"${subscriber}.onComplete", t)
    }
  }

  final def tryRequest(subscription: Subscription, demand: Long): Unit = {
    if (subscription eq null)
      throw new IllegalStateException("Subscription must be not null on request() call, rule 1.3")
    try subscription.request(demand)
    catch {
      case NonFatal(t) =>
        throw new SignalThrewException("It is illegal to throw exceptions from request(), rule 3.16", t)
    }
  }

  final def tryCancel(subscription: Subscription): Unit = {
    if (subscription eq null)
      throw new IllegalStateException("Subscription must be not null on cancel() call, rule 1.3")
    try subscription.cancel()
    catch {
      case NonFatal(t) =>
        throw new SignalThrewException("It is illegal to throw exceptions from cancel(), rule 3.15", t)
    }
  }

}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] case object CancelledSubscription extends Subscription {
  override def request(elements: Long): Unit = ()
  override def cancel(): Unit = ()
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] case object NoopSubscriptionTimeout extends Cancellable {
  override def cancel() = true
  override def isCancelled = true
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] object ActorSubscriber {

  /**
   * Attach a [[ActorSubscriber]] actor as a [[org.reactivestreams.Subscriber]]
   * to a [[org.reactivestreams.Publisher]] or [[akka.stream.scaladsl.Flow]].
   */
  def apply[T](ref: ActorRef): Subscriber[T] = new ActorSubscriberImpl(ref)

  /**
   * INTERNAL API
   */
  private[akka] final case class OnSubscribe(subscription: Subscription)
    extends DeadLetterSuppression
    with NoSerializationVerificationNeeded

}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] sealed abstract class ActorSubscriberMessage extends DeadLetterSuppression with NoSerializationVerificationNeeded

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] object ActorSubscriberMessage {
  final case class OnNext(element: Any) extends ActorSubscriberMessage
  final case class OnError(cause: Throwable) extends ActorSubscriberMessage
  case object OnComplete extends ActorSubscriberMessage

  /**
   * Java API: get the singleton instance of the `OnComplete` message
   */
  def onCompleteInstance = OnComplete
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] trait RequestStrategy {

  /**
   * Invoked by the [[ActorSubscriber]] after each incoming message to
   * determine how many more elements to request from the stream.
   *
   * @param remainingRequested current remaining number of elements that
   *   have been requested from upstream but not received yet
   * @return demand of more elements from the stream, returning 0 means that no
   *   more elements will be requested for now
   */
  def requestDemand(remainingRequested: Int): Int
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] case object ZeroRequestStrategy extends RequestStrategy {
  def requestDemand(remainingRequested: Int): Int = 0

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] abstract class MaxInFlightRequestStrategy(max: Int) extends RequestStrategy {

  /**
   * Concrete subclass must implement this method to define how many
   * messages that are currently in progress or queued.
   */
  def inFlightInternally: Int

  /**
   * Elements will be requested in minimum batches of this size.
   * Default is 5. Subclass may override to define the batch size.
   */
  def batchSize: Int = 5

  override def requestDemand(remainingRequested: Int): Int = {
    val batch = math.min(batchSize, max)
    if ((remainingRequested + inFlightInternally) <= (max - batch))
      math.max(0, max - remainingRequested - inFlightInternally)
    else 0
  }
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] trait ActorSubscriber extends Actor {
  import ActorSubscriber._
  import ActorSubscriberMessage._

  private[this] val state = ActorSubscriberState(context.system)
  private[this] var subscription: Option[Subscription] = None
  private[this] var requested: Long = 0
  private[this] var _canceled = false

  protected def requestStrategy: RequestStrategy

  final def canceled: Boolean = _canceled

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case _: OnNext =>
      requested -= 1
      if (!_canceled) {
        super.aroundReceive(receive, msg)
        request(requestStrategy.requestDemand(remainingRequested))
      }
    case OnSubscribe(sub) =>
      if (subscription.isEmpty) {
        subscription = Some(sub)
        if (_canceled) {
          context.stop(self)
          sub.cancel()
        } else if (requested != 0)
          sub.request(remainingRequested)
      } else
        sub.cancel()
    case OnComplete | OnError(_) =>
      if (!_canceled) {
        _canceled = true
        super.aroundReceive(receive, msg)
      }
    case _ =>
      super.aroundReceive(receive, msg)
      request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPreStart(): Unit = {
    super.aroundPreStart()
    request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostRestart(reason: Throwable): Unit = {
    state.get(self).foreach { s =>
      // restore previous state
      subscription = s.subscription
      requested = s.requested
      _canceled = s.canceled
    }
    state.remove(self)
    super.aroundPostRestart(reason)
    request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorSubscriberState.State(subscription, requested, _canceled))
    super.aroundPreRestart(reason, message)
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostStop(): Unit = {
    state.remove(self)
    if (!_canceled) subscription.foreach(_.cancel())
    super.aroundPostStop()
  }

  /**
   * Request a number of elements from upstream.
   */
  protected def request(elements: Long): Unit =
    if (elements > 0 && !_canceled) {
      // if we don't have a subscription yet, it will be requested when it arrives
      subscription.foreach(_.request(elements))
      requested += elements
    }

  /**
   * Cancel upstream subscription.
   * No more elements will be delivered after cancel.
   *
   * The [[ActorSubscriber]] will be stopped immediately after signaling cancellation.
   * In case the upstream subscription has not yet arrived the Actor will stay alive
   * until a subscription arrives, cancel it and then stop itself.
   */
  protected def cancel(): Unit =
    if (!_canceled) {
      subscription match {
        case Some(s) =>
          context.stop(self)
          s.cancel()
        case _ =>
          _canceled = true // cancel will be signaled once a subscription arrives
      }
    }

  /**
   * The number of stream elements that have already been requested from upstream
   * but not yet received.
   */
  protected def remainingRequested: Int = longToIntMax(requested)

  private def longToIntMax(n: Long): Int =
    if (n > Int.MaxValue) Int.MaxValue
    else n.toInt
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] final class ActorSubscriberImpl[T](val impl: ActorRef) extends Subscriber[T] {
  import ActorSubscriberMessage._
  override def onError(cause: Throwable): Unit = {
    ReactiveStreamsCompliance.requireNonNullException(cause)
    impl ! OnError(cause)
  }
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(element: T): Unit = {
    ReactiveStreamsCompliance.requireNonNullElement(element)
    impl ! OnNext(element)
  }
  override def onSubscribe(subscription: Subscription): Unit = {
    ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
    impl ! ActorSubscriber.OnSubscribe(subscription)
  }
}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] object ActorSubscriberState extends ExtensionId[ActorSubscriberState] with ExtensionIdProvider {
  override def get(system: ActorSystem): ActorSubscriberState = super.get(system)

  override def lookup() = ActorSubscriberState

  override def createExtension(system: ExtendedActorSystem): ActorSubscriberState =
    new ActorSubscriberState

  final case class State(subscription: Option[Subscription], requested: Long, canceled: Boolean)

}

/**
 * Internal API
 *
 * Copy from akka-stream
 */
@InternalApi private[akka] class ActorSubscriberState extends Extension {
  import ActorSubscriberState.State
  private val state = new ConcurrentHashMap[ActorRef, State]

  def get(ref: ActorRef): Option[State] = Option(state.get(ref))

  def set(ref: ActorRef, s: State): Unit = state.put(ref, s)

  def remove(ref: ActorRef): Unit = state.remove(ref)
}
