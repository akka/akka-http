/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File

import akka.NotUsed
import akka.http.scaladsl.model.{ Multipart, _ }
import akka.http.scaladsl.server.{ MissingFormFieldRejection, Route, RoutingSpec }
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

  "the storeUploadedFile directive" should {
    val data = s"<int>${"42" * 1000000}</int>" // ~2MB of data

    def withUpload(entityType: String, formDataUpload: Multipart.FormData) =
      s"for $entityType" should {
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

        "fail when file cannot be written" in {
          val path = new MockFailingWritePath

          val route =
            storeUploadedFile("fieldName", _ => path.toFile) { (info, tmpFile) =>
              complete(info.toString)
            }
          Post("/", formDataUpload) ~> Route.seal(route) ~> check {
            status shouldEqual StatusCodes.InternalServerError
          }
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

    def withUpload(entityType: String, formDataUpload: Multipart.FormData) = {
      s"for $entityType" should {
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

        "fail when file cannot be written" in {
          val path = new MockFailingWritePath

          val route =
            storeUploadedFiles("fieldName", _ => path.toFile) { infos =>
              complete(infos.mkString(", "))
            }
          Post("/", formDataUpload) ~> Route.seal(route) ~> check {
            status shouldEqual StatusCodes.InternalServerError
          }
        }

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

/** Mock Path implementation that allows to create a FileChannel that fails writes */
class MockFailingWritePath extends java.nio.file.Path { selfPath =>
  import java.net.URI
  import java.nio.{ ByteBuffer, MappedByteBuffer }
  import java.nio.channels.{ FileChannel, FileLock, ReadableByteChannel, SeekableByteChannel, WritableByteChannel }
  import java.nio.file.attribute.{ BasicFileAttributes, FileAttribute, FileAttributeView, UserPrincipalLookupService }
  import java.nio.file.spi.FileSystemProvider
  import java.nio.file.{ AccessMode, CopyOption, DirectoryStream, FileStore, FileSystem, LinkOption, OpenOption, Path, PathMatcher, WatchEvent, WatchKey, WatchService }
  import java.{ lang, util }

  override def getFileSystem: FileSystem =
    new FileSystem {
      override def provider(): FileSystemProvider = new FileSystemProvider {
        override def getScheme: String = ???
        override def newFileSystem(uri: URI, env: util.Map[String, _]): FileSystem = ???
        override def getFileSystem(uri: URI): FileSystem = ???
        override def getPath(uri: URI): Path = ???
        override def newByteChannel(path: Path, options: util.Set[_ <: OpenOption], attrs: FileAttribute[_]*): SeekableByteChannel = ???
        override def newDirectoryStream(dir: Path, filter: DirectoryStream.Filter[_ >: Path]): DirectoryStream[Path] = ???
        override def createDirectory(dir: Path, attrs: FileAttribute[_]*): Unit = ???
        override def delete(path: Path): Unit = ()
        override def copy(source: Path, target: Path, options: CopyOption*): Unit = ???
        override def move(source: Path, target: Path, options: CopyOption*): Unit = ???
        override def isSameFile(path: Path, path2: Path): Boolean = ???
        override def isHidden(path: Path): Boolean = ???
        override def getFileStore(path: Path): FileStore = ???
        override def checkAccess(path: Path, modes: AccessMode*): Unit = ???
        override def getFileAttributeView[V <: FileAttributeView](path: Path, `type`: Class[V], options: LinkOption*): V = ???
        override def readAttributes[A <: BasicFileAttributes](path: Path, `type`: Class[A], options: LinkOption*): A = ???
        override def readAttributes(path: Path, attributes: String, options: LinkOption*): util.Map[String, AnyRef] = ???
        override def setAttribute(path: Path, attribute: String, value: Any, options: LinkOption*): Unit = ???
        override def newFileChannel(path: Path, options: util.Set[_ <: OpenOption], attrs: FileAttribute[_]*): FileChannel =
          new FileChannel {
            override def read(dst: ByteBuffer): Int = ???
            override def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long = ???
            override def write(src: ByteBuffer): Int = throw new RuntimeException
            override def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long = throw new RuntimeException
            override def position(): Long = ???
            override def position(newPosition: Long): FileChannel = ???
            override def size(): Long = ???
            override def truncate(size: Long): FileChannel = ???
            override def force(metaData: Boolean): Unit = ???
            override def transferTo(position: Long, count: Long, target: WritableByteChannel): Long = ???
            override def transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = ???
            override def read(dst: ByteBuffer, position: Long): Int = ???
            override def write(src: ByteBuffer, position: Long): Int = ???
            override def map(mode: FileChannel.MapMode, position: Long, size: Long): MappedByteBuffer = ???
            override def lock(position: Long, size: Long, shared: Boolean): FileLock = ???
            override def tryLock(position: Long, size: Long, shared: Boolean): FileLock = ???
            override def implCloseChannel(): Unit = throw new RuntimeException
          }
      }
      override def close(): Unit = ???
      override def isOpen: Boolean = ???
      override def isReadOnly: Boolean = ???
      override def getSeparator: String = ???
      override def getRootDirectories: lang.Iterable[Path] = ???
      override def getFileStores: lang.Iterable[FileStore] = ???
      override def supportedFileAttributeViews(): util.Set[String] = ???
      override def getPath(first: String, more: String*): Path = ???
      override def getPathMatcher(syntaxAndPattern: String): PathMatcher = ???
      override def getUserPrincipalLookupService: UserPrincipalLookupService = ???
      override def newWatchService(): WatchService = ???
    }
  override def isAbsolute: Boolean = ???
  override def getRoot: Path = ???
  override def getFileName: Path = ???
  override def getParent: Path = ???
  override def getNameCount: Int = ???
  override def getName(index: Int): Path = ???
  override def subpath(beginIndex: Int, endIndex: Int): Path = ???
  override def startsWith(other: Path): Boolean = ???
  override def startsWith(other: String): Boolean = ???
  override def endsWith(other: Path): Boolean = ???
  override def endsWith(other: String): Boolean = ???
  override def normalize(): Path = ???
  override def resolve(other: Path): Path = ???
  override def resolve(other: String): Path = ???
  override def resolveSibling(other: Path): Path = ???
  override def resolveSibling(other: String): Path = ???
  override def relativize(other: Path): Path = ???
  override def toUri: URI = ???
  override def toAbsolutePath: Path = ???
  override def toRealPath(options: LinkOption*): Path = ???
  override def toFile: File = new File("") {
    override def toPath: Path = selfPath
  }
  override def register(watcher: WatchService, events: Array[WatchEvent.Kind[_]], modifiers: WatchEvent.Modifier*): WatchKey = ???
  override def register(watcher: WatchService, events: WatchEvent.Kind[_]*): WatchKey = ???
  override def iterator(): util.Iterator[Path] = ???
  override def compareTo(other: Path): Int = ???
}
