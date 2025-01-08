/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HttpMethodSpec extends AnyWordSpec with Matchers {
  "HttpMethod.custom()" should {
    "accept a valid name" in {
      HttpMethod.custom("Yes.Thi$_is_1~'VAL|D`_me+h*d-^ame!#%&")
    }
    "validate that an invalid character is not present" in {
      an[Exception] should be thrownBy HttpMethod.custom("INJECT /path HTTP/1.1\r\n")
      an[Exception] should be thrownBy HttpMethod.custom("INJECT\r\n")
      an[Exception] should be thrownBy HttpMethod.custom("INJECT ")
    }
  }
}
