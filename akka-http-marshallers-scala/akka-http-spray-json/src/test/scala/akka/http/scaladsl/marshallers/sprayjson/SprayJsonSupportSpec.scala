/*
 * Copyright (C) 2017-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.marshallers.sprayjson

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MessageEntity
import akka.http.scaladsl.model.ws.{ BinaryMessage, Message, TextMessage }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import spray.json.{ JsArray, JsString, JsValue, JsonParser }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.RootJsonFormat

class SprayJsonSupportSpec extends AnyWordSpec with Matchers with ScalaFutures {
  import SprayJsonSupport._
  import SprayJsonSupportSpec._
  import spray.json.DefaultJsonProtocol._

  implicit val exampleFormat: RootJsonFormat[Example] = jsonFormat1(Example.apply)
  implicit val sys: ActorSystem = ActorSystem("SprayJsonSupportSpec")
  implicit val ec: ExecutionContext = sys.dispatcher
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 3.seconds)

  val TestString = "Contains all UTF-8 characters: 2-byte: £, 3-byte: ﾖ, 4-byte: 😁, 4-byte as a literal surrogate pair: \uD83D\uDE01"

  "SprayJsonSupport" should {
    "allow round trip via Marshal / Unmarshal case class <-> HttpEntity" in {
      val init = Example(TestString)

      val js = Marshal(init).to[MessageEntity].futureValue
      val example = Unmarshal(js).to[Example].futureValue

      example should ===(init)
    }
    "allow round trip via Marshal / Unmarshal JsValue <-> HttpEntity" in {
      val init = JsArray(JsString(TestString))

      val js = Marshal(init).to[MessageEntity].futureValue
      val example = Unmarshal(js).to[JsValue].futureValue

      example should ===(init)
    }
    "allow Unmarshalling from ByteString -> case class" in {
      val init = Example(TestString)
      val js = ByteString(s"""{"username": "$TestString"}""")
      val example = Unmarshal(js).to[Example].futureValue

      example should ===(init)
    }
    "allow Marshalling case class -> WebSocket Message" in {
      val init = Example(TestString)

      val message = Marshal(init).to[Message].futureValue

      message match {
        case TextMessage.Strict(text) => JsonParser(text).convertTo[Example] should ===(init)
        case other                    => fail(s"Expected a strict TextMessage but got $other")
      }
    }
    "allow Marshalling JsValue -> WebSocket TextMessage" in {
      val init = JsArray(JsString(TestString))

      val message = Marshal(init).to[TextMessage].futureValue

      message match {
        case TextMessage.Strict(text) => JsonParser(text) should ===(init)
        case other                    => fail(s"Expected a strict TextMessage but got $other")
      }
    }
    "allow round trip via Marshal / Unmarshal case class <-> WebSocket Message" in {
      val init = Example(TestString)

      val message = Marshal(init).to[Message].futureValue
      val example = Unmarshal(message).to[Example].futureValue

      example should ===(init)
    }
    "allow Unmarshalling streamed WebSocket TextMessage -> case class" in {
      val init = Example(TestString)
      val json = s"""{"username": "$TestString"}"""
      val (first, second) = json.splitAt(json.length / 2)
      val message: Message = TextMessage(Source(List(first, second)))

      val example = Unmarshal(message).to[Example].futureValue

      example should ===(init)
    }
    "allow Unmarshalling streamed WebSocket TextMessage -> JsValue" in {
      val init = JsArray(JsString(TestString))
      val json = init.compactPrint
      val (first, second) = json.splitAt(json.length / 2)
      val message: Message = TextMessage(Source(List(first, second)))

      val js = Unmarshal(message).to[JsValue].futureValue

      js should ===(init)
    }
    "fail Unmarshalling a streamed WebSocket TextMessage that does not complete within the timeout" in {
      val message: Message = TextMessage(Source.single("{").concat(Source.never))

      val result = sprayJsValueMessageUnmarshallerWithTimeout(100.millis).apply(message)

      result.failed.futureValue shouldBe a[java.util.concurrent.TimeoutException]
    }
    "fail Unmarshalling a WebSocket BinaryMessage" in {
      val message: Message = BinaryMessage(ByteString(s"""{"username": "$TestString"}"""))

      Unmarshal(message).to[Example].failed.futureValue shouldBe an[IllegalArgumentException]
    }
  }
}

object SprayJsonSupportSpec {
  case class Example(username: String)
}
