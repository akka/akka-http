/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.testkit._

import scala.collection.immutable
import scala.concurrent.duration._

class MultipartDirectivesSpec extends RoutingSpec {

  // tests touches filesystem, so reqs may take longer than the default of 1.second to complete
  implicit val routeTimeout = RouteTestTimeout(3.seconds.dilated)

  "the formAndFiles directive" should {
    def tempDest(name: String)(fileInfo: FileInfo): File = {
      val dest = File.createTempFile(s"akka-http-FileUploadDirectivesSpec-$name", ".tmp")
      dest
    }

    "keep the form and stream expected file fields on disk" in {
      @volatile var file: Option[File] = None

      val route = extractRequestContext { ctx ⇒
        val fileFields = immutable.Seq(FileField("fieldName", tempDest("age") _))
        formAndFiles(fileFields) {
          case PartsAndFiles(form, files) ⇒
            file = files.headOption.map(_._2)
            complete(form.toString)
        }
      }

      val str1 = "foo"
      val field1 = Multipart.FormData.BodyPart.Strict(
        "field1",
        HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
        Map.empty
      )
      val str2 = "bar"
      val field2 = Multipart.FormData.BodyPart.Strict(
        "field2",
        HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
        Map.empty
      )

      val xml = "<int>42</int>"
      val filePart = Multipart.FormData.BodyPart.Strict(
        "fieldName",
        HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
        Map("filename" → "age.xml")
      )
      val multipartFormWithFile = Multipart.FormData(field1, field2, filePart)

      Post("/", multipartFormWithFile) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual Map("field1" -> List("foo"), "field2" -> List("bar")).toString
        file.map(read(_)) shouldEqual Some(xml)
      }
    }
  }

  private def read(file: File): String = {
    val source = scala.io.Source.fromFile(file, "UTF-8")
    try {
      source.mkString
    } finally {
      source.close()
    }
  }
}

