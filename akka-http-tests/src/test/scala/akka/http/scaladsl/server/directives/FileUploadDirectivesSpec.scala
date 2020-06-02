/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ MissingFormFieldRejection, RoutingSpec }
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.testkit._
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

class FileUploadDirectivesSpec extends RoutingSpec with Eventually {

  // tests touches filesystem, so reqs may take longer than the default of 1.second to complete
  implicit val routeTimeout = RouteTestTimeout(6.seconds.dilated)

  "the uploadedFile directive" should {

    "write a posted file to a temporary file on disk" in {

      val xml = "<int>42</int>"

      val simpleMultipartUpload =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
          Map("filename" -> "age.xml")))

      @volatile var file: Option[File] = None

      try {
        Post("/", simpleMultipartUpload) ~> {
          uploadedFile("fieldName") {
            case (info, tmpFile) =>
              file = Some(tmpFile)
              complete(info.toString)
          }
        } ~> check {
          file.isDefined shouldEqual true
          responseAs[String] shouldEqual FileInfo("fieldName", "age.xml", ContentTypes.`text/xml(UTF-8)`).toString
          read(file.get) shouldEqual xml
        }
      } finally {
        file.foreach(_.delete())
      }
    }
  }

  "the storeUploadedFile directive" should {
    val data = s"<int>${"42" * 1000000}</int>" // ~2MB of data

    def withUpload(entityType: String, formDataUpload: Multipart.FormData) =
      s"write a posted file to a temporary file on disk from $entityType entity" in {
        @volatile var file: Option[File] = None

        def tempDest(fileInfo: FileInfo): File = {
          val dest = File.createTempFile("akka-http-FileUploadDirectivesSpec", ".tmp")
          file = Some(dest)
          dest
        }

        try {
          Post("/", formDataUpload) ~>
            storeUploadedFile("fieldName", tempDest) { (info, tmpFile) =>
              complete(info.toString)
            } ~> check {
              file.isDefined shouldEqual true
              responseAs[String] shouldEqual FileInfo("fieldName", "age.xml", ContentTypes.`text/xml(UTF-8)`).toString
              read(file.get) shouldEqual data
            }
        } finally {
          file.foreach(_.delete())
        }
      }

    withUpload(
      "strict",
      Multipart.FormData(Multipart.FormData.BodyPart.Strict(
        "fieldName",
        HttpEntity(ContentTypes.`text/xml(UTF-8)`, data),
        Map("filename" -> "age.xml"))))

    withUpload(
      "streamed",
      Multipart.FormData(Multipart.FormData.BodyPart(
        "fieldName",
        HttpEntity.IndefiniteLength(ContentTypes.`text/xml(UTF-8)`, inChunks(data)),
        Map("filename" -> "age.xml"))))
  }

  "the storeUploadedFiles directive" should {
    val txt = "42" * 1000000 // ~2MB of data
    val xml = s"<int>$txt</int>" // ~2MB of data

    def withUpload(entityType: String, formDataUpload: Multipart.FormData) =
      s"write all posted files to a temporary file on disk from $entityType entity" in {
        @volatile var files: Seq[File] = Nil

        def tempDest(fileInfo: FileInfo): File = {
          val dest = File.createTempFile("akka-http-FileUploadDirectivesSpec", ".tmp")
          files = files :+ dest
          dest
        }

        try {
          Post("/", formDataUpload) ~> {
            storeUploadedFiles("fieldName", tempDest) { fields =>
              val content = fields.foldLeft("") {
                case (acc, (fileInfo, tmpFile)) =>
                  acc + read(tmpFile)
              }
              complete(content)
            }
          } ~> check {
            val response = responseAs[String]
            response shouldEqual files.map(read).mkString
            response shouldEqual txt + xml
          }
        } finally {
          files.foreach(_.delete())
        }
      }

    withUpload(
      "strict",
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, txt),
          Map("filename" -> "age.txt")),
        Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
          Map("filename" -> "age.xml"))))

    withUpload(
      "streamed",
      Multipart.FormData(
        Multipart.FormData.BodyPart(
          "fieldName",
          HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, inChunks(txt)),
          Map("filename" -> "age.txt")),
        Multipart.FormData.BodyPart(
          "fieldName",
          HttpEntity.IndefiniteLength(ContentTypes.`text/xml(UTF-8)`, inChunks(xml)),
          Map("filename" -> "age.xml"))))
  }

  "the fileUpload directive" should {

    def echoAsAService =
      extractRequestContext { ctx =>
        fileUpload("field1") {
          case (info, bytes) =>
            // stream the bytes somewhere
            val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) => all ++ bytes }

            // sum all individual file sizes
            onSuccess(allBytesF) { allBytes =>
              complete(allBytes)
            }
        }
      }

    def streamingEcho =
      fileUpload("field2") {
        case (_, bytes) =>
          complete(HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, bytes))
      }

    "echo a strict file upload" in {
      val route = echoAsAService

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }
    }

    "echo a streaming file upload" in {
      val snip = "Asdfasdfasdfasdf"
      val payload = Source(List.fill(100)(ByteString(snip)))

      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart(
            "field2",
            HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, payload),
            Map("filename" -> "data2.txt")
          )
        )

      Post("/", multipartForm) ~> streamingEcho ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual (snip * 100)
      }
    }

    "echo a streaming file upload when there are other parts before and after it" in {
      val snip = "Asdfasdfasdfasdf"
      val payload = Source(List.fill(100)(ByteString(snip)))

      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart(
            "field1",
            HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, Source(List.fill(100)(ByteString("field1data")))),
            Map("filename" -> "data1.txt")
          ),
          Multipart.FormData.BodyPart(
            "field2",
            HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, payload),
            Map("filename" -> "data2.txt")
          ),
          Multipart.FormData.BodyPart(
            "field3",
            HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, Source(List.fill(100)(ByteString("field3data")))),
            Map("filename" -> "data3.txt")
          )
        )

      Post("/", multipartForm) ~> streamingEcho ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual (snip * 100)
      }
    }

    "echo the first file upload if multiple with the same name are posted" in {
      val route = echoAsAService

      val str1 = "some data"
      val str2 = "other data"
      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
            Map("filename" -> "data1.txt")),
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
            Map("filename" -> "data2.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "reject the file upload if the field name is missing" in {
      val route =
        extractRequestContext { ctx =>
          fileUpload("missing") {
            case (info, bytes) =>
              // stream the bytes somewhere
              val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) => all ++ bytes }

              // sum all individual file sizes
              onSuccess(allBytesF) { allBytes =>
                complete(allBytes)
              }
          }
        }

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        rejection shouldEqual MissingFormFieldRejection("missing")
      }

    }

    "not cancel the stream after providing the expected part" in {
      val route = echoAsAService
      val str1 = "some data"

      @volatile var secondWasFullyRead = false
      val secondSource =
        Source.fromIterator(() => Iterator.from(1))
          .take(100)
          .map { i =>
            if (i == 100) secondWasFullyRead = true
            akka.util.ByteString("abcdefghij")
          }

      val multipartForm =
        Multipart.FormData(
          Source(
            Vector(
              Multipart.FormData.BodyPart.Strict(
                "field1",
                HttpEntity(str1),
                Map("filename" -> "data1.txt")
              ),
              Multipart.FormData.BodyPart(
                "field2",
                HttpEntity.IndefiniteLength(ContentTypes.`application/octet-stream`, secondSource)
              )
            )
          )
        )

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
        eventually {
          secondWasFullyRead shouldEqual true
        }
      }
    }

    "not be head-of-line-blocked if there is another big part before the part we are interested in" in {
      val route = echoAsAService
      val str1 = "some data"

      val firstSource =
        Source.repeat(ByteString("abcdefghij" * 100))
          .take(1000) // 1MB

      val multipartForm =
        Multipart.FormData(
          Source(
            Vector(
              // big part comes before the one we are interested in
              Multipart.FormData.BodyPart(
                "field2",
                HttpEntity.IndefiniteLength(ContentTypes.`application/octet-stream`, firstSource)
              ),
              Multipart.FormData.BodyPart.Strict(
                "field1",
                HttpEntity(str1),
                Map("filename" -> "data1.txt")
              )
            )
          )
        )

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }
    }
  }

  "the fileUploadAll directive" should {

    def echoAsAService =
      extractRequestContext { ctx =>
        fileUploadAll("field1") { files =>
          complete {
            Future.traverse(files) { // all the files can be processed in parallel because they are buffered on disk
              case (info, bytes) =>
                // concatenate all data from a single
                bytes.runFold(ByteString.empty)(_ ++ _)
            }.map(_.reduce(_ ++ _)) // and then from all files
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
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    val str1 = "42" * 1000000 // ~2MB of data
    val str2 = "23" * 1000000 // ~2MB of data

    def withUpload(entityType: String, formDataUpload: Multipart.FormData) =
      s"stream all file uploads if multiple with the same name are posted as $entityType entities" in {
        val route = echoAsAService

        Post("/", formDataUpload) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual (str1 + str2)
        }
      }
    withUpload(
      "strict",
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")),
        Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
          Map("filename" -> "data2.txt"))))

    withUpload(
      "streamed",
      Multipart.FormData(
        Multipart.FormData.BodyPart(
          "field1",
          HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, inChunks(str1)),
          Map("filename" -> "data1.txt")),
        Multipart.FormData.BodyPart(
          "field1",
          HttpEntity.IndefiniteLength(ContentTypes.`text/plain(UTF-8)`, inChunks(str2)),
          Map("filename" -> "data2.txt"))))

    "reject the file upload if the field name is missing" in {
      val route =
        extractRequestContext { ctx =>
          fileUpload("missing") {
            case (info, bytes) =>
              // stream the bytes somewhere
              val allBytesF = bytes.runFold(ByteString.empty) { (all, bytes) => all ++ bytes }

              // sum all individual file sizes
              onSuccess(allBytesF) { allBytes =>
                complete(allBytes)
              }
          }
        }

      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        rejection shouldEqual MissingFormFieldRejection("missing")
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

  private def inChunks(input: String, chunkSize: Int = 10000): Source[ByteString, NotUsed] =
    Source.fromIterator(() => input.grouped(10000).map(ByteString(_)))
}
