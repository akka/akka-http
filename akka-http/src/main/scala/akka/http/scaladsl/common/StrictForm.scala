/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.common

import scala.annotation.implicitNotFound
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.stream.Materializer
import akka.http.ccompat._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import FastFuture._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException

/**
 * Read-only abstraction on top of `application/x-www-form-urlencoded` and multipart form data,
 * allowing joint unmarshalling access to either kind, **if** you supply both, a [[akka.http.scaladsl.unmarshalling.FromStringUnmarshaller]]
 * as well as a [[akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller]] for the target type `T`.
 * Note: In order to allow for random access to the field values streamed multipart form data are strictified!
 * Don't use this abstraction on potentially unbounded forms (e.g. large file uploads).
 *
 * If you only need to consume one type of form (`application/x-www-form-urlencoded` *or* multipart) then
 * simply unmarshal directly to the respective form abstraction ([[akka.http.scaladsl.model.FormData]] or [[akka.http.scaladsl.model.Multipart.FormData]])
 * rather than going through [[StrictForm]].
 *
 * Simple usage example:
 * {{{
 * val strictFormFuture = Unmarshal(entity).to[StrictForm]
 * val fooFieldUnmarshalled: Future[T] =
 *   strictFormFuture flatMap { form =>
 *     Unmarshal(form field "foo").to[T]
 *   }
 * }}}
 */
sealed abstract class StrictForm {
  def fields: immutable.Seq[(String, StrictForm.Field)]
  def field(name: String): Option[StrictForm.Field] = fields collectFirst { case (`name`, field) => field }
}

object StrictForm {
  // TODO: make timeout configurable
  val toStrictTimeout = 10.seconds

  sealed trait Field
  object Field {
    private[http] def fromString(value: String): Field = FromString(value)

    private[StrictForm] final case class FromString(value: String) extends Field
    private[StrictForm] final case class FromPart(value: Multipart.FormData.BodyPart.Strict) extends Field

    implicit def unmarshaller[T](implicit um: FieldUnmarshaller[T]): FromStrictFormFieldUnmarshaller[T] =
      Unmarshaller.withMaterializer(implicit ec => implicit mat => {
        case FromString(value) => um.unmarshalString(value)
        case FromPart(value)   => um.unmarshalPart(value)
      })

    def unmarshallerFromFSU[T](fsu: FromStringUnmarshaller[T]): FromStrictFormFieldUnmarshaller[T] =
      Unmarshaller.withMaterializer(implicit ec => implicit mat => {
        case FromString(value) => fsu(value)
        case FromPart(value) =>
          val charsetName = value.entity.contentType.asInstanceOf[ContentType.NonBinary].charset.nioCharset.name
          fsu(value.entity.data.decodeString(charsetName))
      })

    @implicitNotFound(
      "In order to unmarshal a `StrictForm.Field` to type `${T}` you need to supply a " +
        "`FromStringUnmarshaller[${T}]` and/or a `FromEntityUnmarshaller[${T}]`")
    sealed trait FieldUnmarshaller[T] {
      def unmarshalString(value: String)(implicit ec: ExecutionContext, mat: Materializer): Future[T]
      def unmarshalPart(value: Multipart.FormData.BodyPart.Strict)(implicit ec: ExecutionContext, mat: Materializer): Future[T]
    }
    object FieldUnmarshaller extends LowPrioImplicits {
      implicit def fromBoth[T](implicit fsu: FromStringUnmarshaller[T], feu: FromEntityUnmarshaller[T]): FieldUnmarshaller[T] =
        new FieldUnmarshaller[T] {
          def unmarshalString(value: String)(implicit ec: ExecutionContext, mat: Materializer) = fsu(value)
          def unmarshalPart(value: Multipart.FormData.BodyPart.Strict)(implicit ec: ExecutionContext, mat: Materializer) = feu(value.entity)
        }

      implicit val stringFieldUnmarshaller: FieldUnmarshaller[String] =
        new FieldUnmarshaller[String] {
          def unmarshalString(value: String)(implicit ec: ExecutionContext, mat: Materializer): Future[String] = FastFuture.successful(value)
          def unmarshalPart(value: Multipart.FormData.BodyPart.Strict)(implicit ec: ExecutionContext, mat: Materializer): Future[String] =
            Unmarshaller.stringUnmarshaller(value.entity)
        }
    }
    sealed abstract class LowPrioImplicits {
      implicit def fromFSU[T](implicit fsu: FromStringUnmarshaller[T]): FieldUnmarshaller[T] =
        new FieldUnmarshaller[T] {
          def unmarshalString(value: String)(implicit ec: ExecutionContext, mat: Materializer) = fsu(value)
          def unmarshalPart(value: Multipart.FormData.BodyPart.Strict)(implicit ec: ExecutionContext, mat: Materializer) = {
            val charsetName = value.entity.contentType.asInstanceOf[ContentType.NonBinary].charset.nioCharset.name
            fsu(value.entity.data.decodeString(charsetName))
          }
        }
      implicit def fromFEU[T](implicit feu: FromEntityUnmarshaller[T]): FieldUnmarshaller[T] =
        new FieldUnmarshaller[T] {
          def unmarshalString(value: String)(implicit ec: ExecutionContext, mat: Materializer) = feu(HttpEntity(value))
          def unmarshalPart(value: Multipart.FormData.BodyPart.Strict)(implicit ec: ExecutionContext, mat: Materializer) = feu(value.entity)
        }
    }
  }

  implicit def unmarshaller(implicit
    formDataUM: FromEntityUnmarshaller[FormData],
                            multipartUM: FromEntityUnmarshaller[Multipart.FormData]): FromEntityUnmarshaller[StrictForm] =
    Unmarshaller.withMaterializer { implicit ec => implicit fm =>
      entity =>

        def tryUnmarshalToQueryForm: Future[StrictForm] =
          for (formData <- formDataUM(entity).fast) yield {
            new StrictForm {
              val fields = formData.fields.iterator.map { case (name, value) => name -> Field.FromString(value) }.to(scala.collection.immutable.IndexedSeq)
            }
          }

        def tryUnmarshalToMultipartForm: Future[StrictForm] =
          for {
            multiPartFD <- multipartUM(entity).fast
            strictMultiPartFD <- multiPartFD.toStrict(toStrictTimeout).fast
          } yield {
            new StrictForm {
              val fields = strictMultiPartFD.strictParts.iterator.map {
                case x: Multipart.FormData.BodyPart.Strict => x.name -> Field.FromPart(x)
              }.to(scala.collection.immutable.IndexedSeq)
            }
          }

        tryUnmarshalToQueryForm.fast.recoverWith {
          case UnsupportedContentTypeException(supported) =>
            tryUnmarshalToMultipartForm.fast.recoverWith {
              case e: UnsupportedContentTypeException =>
                FastFuture.failed(UnsupportedContentTypeException(supported ++ e.supported, e.actualContentType))
            }
        }
    }

  /**
   * Simple model for strict file content in a multipart form data part.
   */
  final case class FileData(filename: Option[String], entity: HttpEntity.Strict)

  object FileData {
    implicit val unmarshaller: FromStrictFormFieldUnmarshaller[FileData] =
      Unmarshaller strict {
        case Field.FromString(_)  => throw UnsupportedContentTypeException(None, MediaTypes.`application/x-www-form-urlencoded`)
        case Field.FromPart(part) => FileData(part.filename, part.entity)
      }
  }
}
