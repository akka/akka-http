/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File

import akka.Done
import akka.annotation.ApiMayChange
import akka.http.scaladsl.server.{ Directive, Directive1, MissingFormFieldRejection }
import akka.http.scaladsl.model.{ ContentType, Multipart }
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import akka.stream.scaladsl._
import akka.http.javadsl
import akka.http.scaladsl.server.RouteResult

import scala.util.{ Failure, Success }

/**
 * @groupname fileupload File upload directives
 * @groupprio fileupload 80
 */
trait FileUploadDirectives {

  import BasicDirectives._
  import FutureDirectives._
  import MarshallingDirectives._

  /**
   * Streams the bytes of the file submitted using multipart with the given file name into a temporary file on disk.
   * If there is an error writing to disk the request will be failed with the thrown exception, if there is no such
   * field the request will be rejected, if there are multiple file parts with the same name, the first one will be
   * used and the subsequent ones ignored.
   *
   * @group fileupload
   */
  @deprecated("Deprecated in favor of storeUploadedFile which allows to specify a file to store the upload in.", "10.0.11")
  def uploadedFile(fieldName: String): Directive1[(FileInfo, File)] =
    storeUploadedFile(fieldName, _ => File.createTempFile("akka-http-upload", ".tmp")).tmap(Tuple1(_))

  /**
   * Streams the bytes of the file submitted using multipart with the given file name into a designated file on disk.
   * If there is an error writing to disk the request will be failed with the thrown exception, if there is no such
   * field the request will be rejected, if there are multiple file parts with the same name, the first one will be
   * used and the subsequent ones ignored.
   *
   * @group fileupload
   */
  @ApiMayChange
  def storeUploadedFile(fieldName: String, destFn: FileInfo => File): Directive[(FileInfo, File)] =
    extractRequestContext.flatMap { ctx =>
      import ctx.executionContext
      import ctx.materializer

      fileUpload(fieldName).flatMap {
        case (fileInfo, bytes) =>

          val dest = destFn(fileInfo)
          val uploadedF: Future[(FileInfo, File)] =
            bytes
              .runWith(FileIO.toPath(dest.toPath))
              .map(_ => (fileInfo, dest))
              .recoverWith {
                case ex =>
                  dest.delete()
                  throw ex
              }

          onSuccess(uploadedF)
      }
    }

  /**
   * Streams the bytes of the file submitted using multipart with the given field name into designated files on disk.
   * If there is an error writing to disk the request will be failed with the thrown exception, if there is no such
   * field the request will be rejected. Stored files are cleaned up on exit but not on failure.
   *
   * @group fileupload
   */
  @ApiMayChange
  def storeUploadedFiles(fieldName: String, destFn: FileInfo => File): Directive1[immutable.Seq[(FileInfo, File)]] =
    entity(as[Multipart.FormData]).flatMap { formData =>
      extractRequestContext.flatMap { ctx =>
        implicit val mat = ctx.materializer
        implicit val ec = ctx.executionContext

        val uploaded: Source[(FileInfo, File), Any] = formData.parts
          .mapConcat { part =>
            if (part.filename.isDefined && part.name == fieldName) part :: Nil
            else {
              part.entity.discardBytes()
              Nil
            }
          }
          .mapAsync(1) { part =>
            val fileInfo = FileInfo(part.name, part.filename.get, part.entity.contentType)
            val dest = destFn(fileInfo)

            part.entity.dataBytes.runWith(FileIO.toPath(dest.toPath)).map { _ =>
              (fileInfo, dest)
            }
          }

        val uploadedF = uploaded.runWith(Sink.seq[(FileInfo, File)])

        onSuccess(uploadedF)
      }
    }

  /**
   * Collects each body part that is a multipart file as a tuple containing metadata and a `Source`
   * for streaming the file contents somewhere. If there is no such field the request will be rejected,
   * if there are multiple file parts with the same name, the first one will be used and the subsequent
   * ones ignored.
   *
   * @group fileupload
   */
  def fileUpload(fieldName: String): Directive1[(FileInfo, Source[ByteString, Any])] =
    entity(as[Multipart.FormData]).flatMap { formData =>
      Directive[Tuple1[(FileInfo, Source[ByteString, Any])]] { inner => ctx =>
        import ctx.materializer
        import ctx.executionContext

        // We complete the directive through this promise as soon as we encounter the
        // selected part. This way the inner directive can consume it, after which we will
        // proceed to consume the rest of the request, discarding any follow-up parts.
        val done = Promise[RouteResult]()

        // Streamed multipart data must be processed in a certain way, that is, before you can expect the next part you
        // must have fully read the entity of the current part.
        // That means, we cannot just do `formData.parts.runWith(Sink.seq)` and then look for the part we are interested in
        // but instead, we must actively process all the parts, regardless of whether we are interested in the data or not.
        formData.parts
          .mapAsync(parallelism = 1) {
            case part if !done.isCompleted && part.filename.isDefined && part.name == fieldName =>
              val data = (FileInfo(part.name, part.filename.get, part.entity.contentType), part.entity.dataBytes)
              inner(Tuple1(data))(ctx).map { result =>
                done.success(result)
              }
            case part =>
              part.entity.discardBytes().future
          }
          .runWith(Sink.ignore)
          .onComplete {
            case Success(Done) =>
              if (done.isCompleted)
                () // OK
              else
                done.success(RouteResult.Rejected(MissingFormFieldRejection(fieldName) :: Nil))
            case Failure(cause) =>
              if (done.isCompleted)
                () // consuming the other parts failed though we already started processing the selected part.
              else
                done.failure(cause)
          }

        done.future
      }
    }

  /**
   * Collects each body part that is a multipart file as a tuple containing metadata and a `Source`
   * for streaming the file contents somewhere. If there is no such field the request will be rejected.
   * Files are buffered into temporary files on disk so in-memory buffers don't overflow. The temporary
   * files are cleaned up once materialized, or on exit if the stream is not consumed.
   *
   * @group fileupload
   */
  @ApiMayChange
  def fileUploadAll(fieldName: String): Directive1[immutable.Seq[(FileInfo, Source[ByteString, Any])]] =
    extractRequestContext.flatMap { ctx =>
      implicit val ec = ctx.executionContext

      def tempDest(fileInfo: FileInfo): File = {
        val dest = File.createTempFile("akka-http-upload", ".tmp")
        dest.deleteOnExit()
        dest
      }

      storeUploadedFiles(fieldName, tempDest).map { files =>
        files.map {
          case (fileInfo, src) =>
            val byteSource: Source[ByteString, Any] = FileIO.fromPath(src.toPath)
              .mapMaterializedValue { f =>
                f.onComplete(_ => src.delete())
              }

            (fileInfo, byteSource)
        }
      }
    }
}

object FileUploadDirectives extends FileUploadDirectives

/**
 * Additional metadata about the file being uploaded/that was uploaded using the [[FileUploadDirectives]]
 *
 * @param fieldName Name of the form field the file was uploaded in
 * @param fileName User specified name of the uploaded file
 * @param contentType Content type of the file
 */
final case class FileInfo(fieldName: String, fileName: String, contentType: ContentType) extends javadsl.server.directives.FileInfo {
  override def getFieldName = fieldName
  override def getFileName = fileName
  override def getContentType = contentType
}
