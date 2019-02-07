/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.stream.ActorMaterializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.AkkaSpec

class FormDataSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val formData = FormData(Map("surname" → "Smith", "age" → "42"))

  "The FormData infrastructure" should {
    "properly round-trip the fields of x-www-urlencoded forms" in {
      Marshal(formData).to[HttpEntity]
        .flatMap(Unmarshal(_).to[FormData]).futureValue shouldEqual formData
    }

    "properly marshal x-www-urlencoded forms containing special chars" in {
      val entity = Marshal(FormData(Map("name" → "Smith&Wesson"))).to[HttpEntity]
      entity.flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%26Wesson"
      entity.flatMap(Unmarshal(_).to[HttpEntity]).futureValue.getContentType shouldEqual ContentTypes.`application/x-www-form-urlencoded`

      val entity2 = Marshal(FormData(Map("name" → "Smith+Wesson; hopefully!"))).to[HttpEntity]
      entity2.flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%2BWesson%3B+hopefully%21"
      entity2.flatMap(Unmarshal(_).to[HttpEntity]).futureValue.getContentType shouldEqual ContentTypes.`application/x-www-form-urlencoded`
    }

    "properly marshal empty x-www-urlencoded form" in {
      val entity = Marshal(FormData(Map.empty[String, String])).to[HttpEntity]
      entity.flatMap(Unmarshal(_).to[String]).futureValue shouldBe empty
      entity.flatMap(Unmarshal(_).to[HttpEntity]).futureValue.getContentType shouldEqual ContentTypes.`application/x-www-form-urlencoded`
    }
  }
}
