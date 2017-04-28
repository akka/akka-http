/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File
import java.nio.file.Files

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ MissingFormFieldRejection, RoutingSpec }
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.util.ByteString
import akka.testkit._
import scala.concurrent.Future
import scala.concurrent.duration._

class FileUploadDirectivesSpec extends RoutingSpec {

  // tests touches filesystem, so reqs may take longer than the default of 1.second to complete
  implicit val routeTimeout = RouteTestTimeout(3.seconds.dilated)

  "the uploadedFile directive" should {

    "write a posted file to a temporary file on disk" in {

      val xml = "<int>42</int>"

      val simpleMultipartUpload =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
          Map("filename" → "age.xml")))

      @volatile var file: Option[File] = None

      try {
        Post("/", simpleMultipartUpload) ~> {
          uploadedFile("fieldName") {
            case (info, tmpFile) ⇒
              file = Some(tmpFile)
              complete(info.toString)
          }
        } ~> check {
          file.isDefined === true
          responseAs[String] === FileInfo("fieldName", "age.xml", ContentTypes.`text/xml(UTF-8)`).toString
          read(file.get) === xml
        }
      } finally {
        file.foreach(_.delete())
      }
    }
  }

  "the storeUploadedFile directive" should {

    "write a posted file to a temporary file on disk" in {

      val xml = "<int>42</int>"

      val simpleMultipartUpload =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
          Map("filename" → "age.xml")))

      @volatile var file: Option[File] = None

      def tempDest(fileInfo: FileInfo): File = {
        val dest = File.createTempFile("akka-http-FileUploadDirectivesSpec", ".tmp")
        file = Some(dest)
        dest
      }

      try {
        Post("/", simpleMultipartUpload) ~> {
          storeUploadedFile("fieldName", tempDest) {
            case (info, tmpFile) ⇒
              complete(info.toString)
          }
        } ~> check {
          file.isDefined === true
          responseAs[String] === FileInfo("fieldName", "age.xml", ContentTypes.`text/xml(UTF-8)`).toString
          read(file.get) === xml
        }
      } finally {
        file.foreach(_.delete())
      }
    }
  }

  "the storeUploadedFiles directive" should {

    "write all posted files to a temporary file on disk" in {

      val txt = "42"
      val xml = "<int>42</int>"

      val complexMultipartUpload =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, txt),
            Map("filename" → "age.txt")),
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
            Map("filename" → "age.xml")))

      @volatile var files: Seq[File] = Nil

      def tempDest(fileInfo: FileInfo): File = {
        val dest = File.createTempFile("akka-http-FileUploadDirectivesSpec", ".tmp")
        files = files :+ dest
        dest
      }

      try {
        Post("/", complexMultipartUpload) ~> {
          storeUploadedFiles("fieldName", tempDest) { fields ⇒
            val content = fields.foldLeft("") {
              case (acc, (fileInfo, tmpFile)) ⇒
                acc + read(tmpFile)
            }
            complete(content)
          }
        } ~> check {
          responseAs[String] === files.map(read).mkString
        }
      } finally {
        files.foreach(_.delete())
      }
    }
  }

  "the fileUpload directive" should {

    def echoAsAService =
      extractRequestContext { ctx ⇒
        fileUpload("field1") {
          case (info, bytes) ⇒
            // stream the bytes somewhere
            val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) ⇒ all ++ bytes }

            // sum all individual file sizes
            onSuccess(allBytesF) { allBytes ⇒
              complete(allBytes)
            }
        }
      }

    "stream the file upload" in {
      val route = echoAsAService

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" → "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "stream the first file upload if multiple with the same name are posted" in {
      val route = echoAsAService

      val str1 = "some data"
      val str2 = "other data"
      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
            Map("filename" → "data1.txt")),
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
            Map("filename" → "data2.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "reject the file upload if the field name is missing" in {
      val route =
        extractRequestContext { ctx ⇒
          fileUpload("missing") {
            case (info, bytes) ⇒
              // stream the bytes somewhere
              val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) ⇒ all ++ bytes }

              // sum all individual file sizes
              onSuccess(allBytesF) { allBytes ⇒
                complete(allBytes)
              }
          }
        }

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" → "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        rejection === MissingFormFieldRejection("missing")
      }

    }

  }

  "the fileUploadAll directive" should {

    def echoAsAService =
      extractRequestContext { ctx ⇒
        implicit val mat = ctx.materializer

        fileUploadAll("field1") {
          case files ⇒
            val allBytesF = files.foldLeft(Future.successful(ByteString.empty)) {
              case (accF, (info, bytes)) ⇒
                val bytesF = bytes.runFold(ByteString.empty) { (all, bytes) ⇒ all ++ bytes }
                accF.flatMap(acc ⇒ bytesF.map(acc ++ _))
            }

            // sum all individual file sizes
            onSuccess(allBytesF) { allBytes ⇒
              complete(allBytes)
            }
        }
      }

    "stream the file upload" in {
      val route = echoAsAService

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" → "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "stream all file uploads if multiple with the same name are posted" in {
      val route = echoAsAService

      val str1 = "some data"
      val str2 = "other data"
      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
            Map("filename" → "data1.txt")),
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
            Map("filename" → "data2.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual (str1 + str2)
      }

    }

    "reject the file upload if the field name is missing" in {
      val route =
        extractRequestContext { ctx ⇒
          implicit val mat = ctx.materializer

          fileUpload("missing") {
            case (info, bytes) ⇒
              // stream the bytes somewhere
              val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) ⇒ all ++ bytes }

              // sum all individual file sizes
              onSuccess(allBytesF) { allBytes ⇒
                complete(allBytes)
              }
          }
        }

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" → "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        rejection === MissingFormFieldRejection("missing")
      }

    }

  }

  private def read(file: File): String = {
    val in = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](1024)
      in.read(buffer)
      new String(buffer, "UTF-8")
    } finally {
      in.close()
    }
  }

}
