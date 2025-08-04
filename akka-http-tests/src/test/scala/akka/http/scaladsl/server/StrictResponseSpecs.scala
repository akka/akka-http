/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse, StatusCodes }
import akka.stream.scaladsl.Source
import akka.util.ByteString

class StrictResponseSpecs extends RoutingSpec {

  "strict response entities" should {
    "should not include content length if told to omit" in {

      val entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("somebytes"), reportContentLength = false)
      val response = HttpResponse(status = StatusCodes.OK, entity = entity)
      val route = complete(response)

      Get() ~> route ~> check {
        status should ===(StatusCodes.OK)
        responseEntity.contentLengthOption shouldBe None
      }
    }

  }
}
