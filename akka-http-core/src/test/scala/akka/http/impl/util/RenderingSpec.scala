/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.event.Logging
import akka.http.scaladsl.model.headers.RawHeader
import akka.testkit.EventFilter
import org.scalatest.matchers.should.Matchers

import java.nio.charset.Charset
import scala.reflect.{ ClassTag, classTag }

class RenderingSpec extends AkkaSpecWithMaterializer with Matchers {
  override protected def failOnSevereMessages: Boolean = true

  "The StringRendering should" should {
    "correctly render Ints and Longs to decimal" in {
      (new StringRendering ~~ 0).get shouldEqual "0"
      (new StringRendering ~~ 123456789).get shouldEqual "123456789"
      (new StringRendering ~~ -123456789L).get shouldEqual "-123456789"
    }

    "correctly render Ints and Longs to hex" in {
      (new StringRendering ~~% 0).get shouldEqual "0"
      (new StringRendering ~~% 65535).get shouldEqual "ffff"
      (new StringRendering ~~% 65537).get shouldEqual "10001"
      (new StringRendering ~~% -10L).get shouldEqual "fffffffffffffff6"
    }

    "correctly render plain Strings" in {
      (new StringRendering ~~ "").get shouldEqual ""
      (new StringRendering ~~ "hello").get shouldEqual "hello"
    }

    "correctly render escaped Strings" in {
      (new StringRendering ~~# "").get shouldEqual "\"\""
      (new StringRendering ~~# "hello").get shouldEqual "hello"
      (new StringRendering ~~# """hel"lo""").get shouldEqual """"hel\"lo""""
    }
  }

  "Renderings" should {
    trait RenderingSetup {
      type R <: Rendering
      def create(): R
      def result(r: R): String
      def tag: String
    }
    def setup[_R <: Rendering: ClassTag](_create: => _R)(_result: _R => String): RenderingSetup =
      new RenderingSetup {
        override type R = _R
        override def create(): _R = _create
        override def result(r: _R): String = _result(r)
        override val tag: String = classTag[R].runtimeClass.getSimpleName
      }

    val renderings: Seq[RenderingSetup] = Seq(
      setup(new StringRendering)(_.get),
      setup(new ByteArrayRendering(1000, Logging(system, "test").warning))(r => new String(r.get)),
      setup(new ByteStringRendering(1000, Logging(system, "test").warning))(_.get.utf8String),
      setup(new CustomCharsetByteStringRendering(Charset.forName("ISO-8859-1"), 1000))(_.get.utf8String)
    )

    renderings.foreach { setup =>
      setup.tag should {
        "render correct headers correctly" in {
          val r = setup.create()
          val rendered = setup.result(r ~~ RawHeader("Test", "value"))

          rendered shouldBe "Test: value\r\n"
        }
        "do not render header with invalid name" in {
          val r = setup.create()
          val rendered =
            EventFilter.warning(pattern = "Invalid outgoing header was discarded").intercept {
              setup.result(r ~~ RawHeader("X-Broken\r-Header", "value"))
            }

          rendered shouldBe ""
        }
        "do not render header with invalid value" in {
          val r = setup.create()
          val rendered =
            EventFilter.warning(pattern = "Invalid outgoing header was discarded").intercept {
              setup.result(r ~~ RawHeader("Test", "broken\nvalue"))
            }

          rendered shouldBe ""
        }
      }
    }
  }
}
