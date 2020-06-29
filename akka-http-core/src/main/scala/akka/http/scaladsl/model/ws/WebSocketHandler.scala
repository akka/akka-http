/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import akka.actor.ActorRef
import akka.annotation.ApiMayChange
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.{ ByteString, Timeout }

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

@ApiMayChange
object WebSocketHandler {
  // auto-strict variants
  def forText(): Builder[String] = ???
  def forBinary(): Builder[ByteString] = ???

  // potentially streaming variants
  def forTextMessage(): Builder[TextMessage] = ???
  def forBinaryMessage(): Builder[BinaryMessage] = ???

  trait Builder[T] {
    def handle(handler: Flow[T, T, Any]): SettingsBuilder
    def mapMessages(handler: T => T): SettingsBuilder = handle(Flow[T].map(handler))
    def flatMapMessages(handler: T => immutable.Iterable[T]): SettingsBuilder = handle(Flow[T].mapConcat(handler))
    def mapMessagesAsync(handler: T => Future[T], parallelism: Int = 1, unordered: Boolean = false): SettingsBuilder
    def flatMapMessagesAsync(handler: T => Future[immutable.Iterable[T]], parallelism: Int = 1, unordered: Boolean = false): SettingsBuilder

    def sinkAndSource(sink: Sink[T, Any], source: Source[T, Any]): SettingsBuilder
    def source(source: Source[T, Any]): SettingsBuilder
    def sink(sink: Sink[T, Any]): SettingsBuilder
  }
  trait SettingsBuilder {
    def withMaxMessageSize(maxSize: Int): this.type
    def withPerMessageTimeout(timeout: FiniteDuration): this.type
    def withHalfClosedConnections(): this.type
    def withIgnoreWrongMessageType(): this.type

    def build(): Flow[Message, Message, Any]
  }

  // FIXME: make less arbitrary / get from config
  val defaultSettings: Settings =
    Settings(
      maxMessageSize = 1000000,
      perMessageTimeout = 10.seconds,
      allowHalfClosed = false,
      ignoreWrongMessageType = false
    )
  case class Settings(
    maxMessageSize:         Int,
    perMessageTimeout:      FiniteDuration,
    allowHalfClosed:        Boolean,
    ignoreWrongMessageType: Boolean
  )
  //private def builder[T](cons: T => Message, decons: Settings => PartialFunction[Message,Future[T]]): Builder[T] = ???
  private def settingsBuilder[T](builder: Settings => Flow[Message, Message, Any]): SettingsBuilder =
    SettingsBuilderImpl(defaultSettings, builder)

  private case class SettingsBuilderImpl(settings: Settings, builder: Settings => Flow[Message, Message, Any]) extends SettingsBuilder {
    override def withMaxMessageSize(maxSize: Int): this.type = set(_.copy(maxMessageSize = maxSize))
    override def withPerMessageTimeout(timeout: FiniteDuration): this.type = set(_.copy(perMessageTimeout = timeout))
    override def withHalfClosedConnections(): this.type = set(_.copy(allowHalfClosed = true))
    override def withIgnoreWrongMessageType(): this.type = set(_.copy(ignoreWrongMessageType = true))

    private def set(s: Settings => Settings): this.type = copy(settings = s(settings)).asInstanceOf[this.type]

    override def build(): Flow[Message, Message, Any] = builder(settings)
  }
}

object WebSocketHandlerExamples {
  // simple text-based Request / Response pattern:
  // synchronous

  val strictHandler: String => String = txt => s"echo: $txt"
  WebSocketHandler
    .forText()
    .mapMessages(strictHandler)
    .build()

  // would correspond to this flow:
  implicit def ec: ExecutionContext = ???
  implicit def mat: Materializer = ???
  val handler: Flow[Message, Message, Any] =
    Flow[Message]
      .mapAsync[Seq[Message]](1) {
        case tm: TextMessage =>
          tm.toStrict(WebSocketHandler.defaultSettings.perMessageTimeout)
            // FIXME: also check maxMessageSize here (or better in toStrict)
            .map(t => TextMessage(strictHandler(t.text)) :: Nil)

        // if ignoreWrongMessageType is false, which is currently the default
        case _: BinaryMessage => Future.failed(new IllegalArgumentException("Unexpected binary WS message"))
      }.mapConcat(identity)

  import akka.pattern.ask
  def actor: ActorRef = ???
  implicit def timeout: Timeout = ???

  // offload to actor
  WebSocketHandler
    .forText()
    .mapMessagesAsync(txt => (actor ? txt).mapTo[String])
    .build()

  // log all incoming data, never send anything
  WebSocketHandler
    .forText()
    .sink(Sink.foreach(println))
    .build()

  // like server pushing
  def eventSource: Source[String, Any] = ???
  WebSocketHandler
    .forText()
    .source(eventSource)
    .build()
}
