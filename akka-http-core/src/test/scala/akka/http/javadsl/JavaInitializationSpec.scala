/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JavaInitializationSpec extends AnyWordSpec with Matchers {

  implicit class HeaderCheck[T](self: T) {
    def =!=(expected: String) = {
      self should !==(null)
      self.toString shouldBe expected
    }
  }

  "EntityTagRange" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.headers.EntityTagRange.`*` =!= "*"
      akka.http.javadsl.model.headers.EntityTagRanges.ALL =!= "*"
    }
  }

  "HttpEncodingRange" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.headers.HttpEncodingRange.`*` =!= "*"
      akka.http.javadsl.model.headers.HttpEncodingRanges.ALL =!= "*"
    }
  }

  "HttpEntity" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.HttpEntity.Empty =!= "HttpEntity.Strict(none/none,0 bytes total)"
      akka.http.javadsl.model.HttpEntities.EMPTY =!= "HttpEntity.Strict(none/none,0 bytes total)"
    }
  }

  "HttpOriginRange" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.headers.HttpOriginRange.`*` =!= "*"
      akka.http.javadsl.model.headers.HttpOriginRanges.ALL =!= "*"
    }
  }

  "LanguageRange" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.headers.LanguageRange.`*` =!= "*" // first we touch the scala one, it should force init the Java one
      akka.http.javadsl.model.headers.LanguageRanges.ALL =!= "*" // this is recommended and should work well too
    }
  }

  "RemoteAddress" should {
    "initializes the right field" in {
      akka.http.scaladsl.model.RemoteAddress.Unknown =!= "unknown"
      akka.http.javadsl.model.RemoteAddresses.UNKNOWN =!= "unknown"
    }
  }

}
