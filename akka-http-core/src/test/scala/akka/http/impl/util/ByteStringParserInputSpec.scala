/*
 * Copyright (C) 2018-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ByteStringParserInputSpec extends AnyWordSpec with Matchers {

  "The ByteStringParserInput" should {
    val parser = new ByteStringParserInput(ByteString("abcde", "ISO-8859-1"))
    "return the correct character for index" in {
      parser.charAt(0) should ===('a')
      parser.charAt(4) should ===('e')
    }

    "return the correct length" in {
      parser.length should ===(5)
    }

    "slice the bytes correctly into a string" in {
      parser.sliceString(0, 3) should ===("abc")
      parser.sliceString(3, 5) should ===("de")
    }

    "slice the bytes correctly into a char array" in {
      val array = parser.sliceCharArray(0, 3)
      array(0) should ===('a')
      array(1) should ===('b')
      array(2) should ===('c')
      array.length should ===(3)
    }

  }

}
