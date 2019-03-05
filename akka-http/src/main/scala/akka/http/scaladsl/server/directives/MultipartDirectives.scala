/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.File

import akka.annotation.ApiMayChange
import akka.http.scaladsl.server.{ Directive1 }
import akka.http.scaladsl.model.{ ContentType, Multipart, HttpEntity }

import scala.collection.immutable
import scala.concurrent.Future
import akka.stream.scaladsl._

/**
 * @groupname multipart Multipart forms directives
 * @groupprio multipart 80
 */
trait MultipartDirectives {

  import BasicDirectives._
  import FutureDirectives._
  import MarshallingDirectives._

  /**
   * Given a list of FileField, the directive will look for parts with a name equal to the FileField#fieldName.
   * These parts will be streamed on disk in the file returned by FileField#fileNameF call. All the other,
   * non-binary and strict part will be gathered in a Map[String, List[String]] similar to what
   * FormFieldDirectives#formFieldMultiMap returns.
   *
   * All other parts will be discarded.
   *
   * Given Seq("file" -> (_ => new File("/tmp/content.txt")))
   *
   * POST /submit
   * Content-Type: multipart/form-data; boundary=------------------------boundary
   * --------------------------boundary
   * Content-Disposition: form-data; name="name"
   * Content-Type: text/plain; charset=UTF-8
   *
   * name_value
   * --------------------------boundary
   * Content-Disposition: form-data; name="version"
   * Content-Type: text/plain; charset=UTF-8
   *
   * version_value
   * --------------------------boundary
   * Content-Disposition: form-data; name="unannounced"; filename="dicarded.txt"
   * Content-Type: text/plain; charset=UTF-8
   *
   * discarded
   * --------------------------boundary
   * Content-Disposition: form-data; name="file"; filename="content.txt"
   * Content-Type: text/plain; charset=UTF-8
   *
   * file content
   * --------------------------boundary--
   *
   *
   * The map would be: Map("name" -> List("name_value"), "version" -> List("version_value"))
   * The list would be List(FileInfo("file", "content.txt", Content.Type.`text/plain`) -> File("/tmp/content.txt"))
   *
   * @group multipart
   */
  @ApiMayChange
  def formAndFiles(
    fileFields: immutable.Seq[FileField]
  ): Directive1[PartsAndFiles] =
    entity(as[Multipart.FormData]).flatMap { formData ⇒
      extractRequestContext.flatMap { ctx ⇒
        implicit val mat = ctx.materializer
        implicit val ec = ctx.executionContext

        val uploadingSink =
          Sink.foldAsync[PartsAndFiles, Multipart.FormData.BodyPart](PartsAndFiles.Empty) {
            (acc, part) ⇒
              def discard(p: Multipart.FormData.BodyPart): Future[PartsAndFiles] = {
                p.entity.discardBytes()
                Future.successful(acc)
              }

              part.filename.map { fileName ⇒
                fileFields.find(_.fieldName == part.name)
                  .map {
                    case FileField(fieldName, destFn) ⇒
                      val fileInfo = FileInfo(part.name, fileName, part.entity.contentType)
                      val dest = destFn(fileInfo)

                      part.entity.dataBytes.runWith(FileIO.toPath(dest.toPath)).map { _ ⇒
                        acc.addFile(fileInfo, dest)
                      }
                  }.getOrElse(discard(part))
              } getOrElse {
                part.entity match {
                  case HttpEntity.Strict(ct, data) if ct.isInstanceOf[ContentType.NonBinary] ⇒
                    val charsetName = ct.asInstanceOf[ContentType.NonBinary].charset.nioCharset.name
                    val partContent = data.decodeString(charsetName)

                    Future.successful(acc.addForm(part.name, partContent))
                  case _ ⇒
                    discard(part)
                }
              }
          }

        val uploadedF = formData.parts.runWith(uploadingSink)

        onSuccess(uploadedF)
      }
    }
}

object MultipartDirectives extends FileUploadDirectives

final case class FileField(fieldName: String, fileNameF: FileInfo ⇒ File)

final case class PartsAndFiles(form: immutable.Map[String, List[String]], files: immutable.Seq[(FileInfo, File)]) {
  final def addForm(fieldName: String, content: String): PartsAndFiles = this.copy(
    form = {
      val existingContent: List[String] = this.form.getOrElse(fieldName, List.empty)
      val newContents: List[String] = content :: existingContent

      this.form + (fieldName -> newContents)
    }
  )
  final def addFile(info: FileInfo, file: File): PartsAndFiles = this.copy(
    files = this.files :+ ((info, file))
  )
}
object PartsAndFiles {
  val Empty = PartsAndFiles(immutable.Map.empty, immutable.Seq.empty)
}
