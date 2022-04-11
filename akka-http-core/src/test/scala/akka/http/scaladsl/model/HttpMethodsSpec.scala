/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import org.scalatest.wordspec.AnyWordSpec

class HttpMethodsSpec extends AnyWordSpec {
  "HttpMethods.getForKeyCaseInsensitive()" must {
    "return HttpMethods.CONNECT" in {
      assert(HttpMethods.getForKeyCaseInsensitive("CONNECT") == Option(HttpMethods.CONNECT))
    }
    "return HttpMethods.DELETE" in {
      assert(HttpMethods.getForKeyCaseInsensitive("Delete") == Option(HttpMethods.DELETE))
    }
    "return HttpMethods.GET" in {
      assert(HttpMethods.getForKeyCaseInsensitive("get") == Option(HttpMethods.GET))
    }
    "return HttpMethods.HEAD" in {
      assert(HttpMethods.getForKeyCaseInsensitive("HeaD") == Option(HttpMethods.HEAD))
    }
    "return HttpMethods.OPTIONS" in {
      assert(HttpMethods.getForKeyCaseInsensitive("oPtIoNs") == Option(HttpMethods.OPTIONS))
    }
  }
}
