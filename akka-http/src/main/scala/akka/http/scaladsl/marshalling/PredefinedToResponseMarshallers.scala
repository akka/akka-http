/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import akka.annotation.InternalApi
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshalling.Marshal.selectMarshallingForContentType
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.util.ConstantFun
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.collection.immutable
import scala.reflect.ClassTag

trait PredefinedToResponseMarshallers extends LowPriorityToResponseMarshallerImplicits {
  import PredefinedToResponseMarshallers._

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

  def fromToEntityMarshaller[T](
    status:  StatusCode                = StatusCodes.OK,
    headers: immutable.Seq[HttpHeader] = Nil)(
    implicit
    m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    fromStatusCodeAndHeadersAndValue compose (t => (status, headers, t))

  implicit val fromResponse: TRM[HttpResponse] = Marshaller.opaque(ConstantFun.scalaIdentityFunction)

  /**
   * Creates a response for a status code. Does not support content-type negotiation but will return
   * a response either with a `text-plain` entity containing the `status.defaultMessage` or an empty entity
   * for status codes that don't allow a response.
   */
  implicit val fromStatusCode: TRM[StatusCode] =
    Marshaller.opaque { status => statusCodeResponse(status) }

  /**
   * Creates a response from status code and headers. Does not support content-type negotiation but will return
   * a response either with a `text-plain` entity containing the `status.defaultMessage` or an empty entity
   * for status codes that don't allow a response.
   */
  implicit val fromStatusCodeAndHeaders: TRM[(StatusCode, immutable.Seq[HttpHeader])] =
    Marshaller.opaque { case (status, headers) => statusCodeResponse(status, headers) }

  implicit def fromStatusCodeAndValue[S, T](implicit sConv: S => StatusCode, mt: ToEntityMarshaller[T]): TRM[(S, T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, value) => (sConv(status), Nil, value) }

  implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S => StatusCode, mt: ToEntityMarshaller[T]): TRM[(S, immutable.Seq[HttpHeader], T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, headers, value) => (sConv(status), headers, value) }

  implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(StatusCode, immutable.Seq[HttpHeader], T)] =
    Marshaller(implicit ec => {
      case (status, headers, value) =>
        mt(value).fast map { marshallings =>
          val mappedMarshallings = marshallings map (_ map (statusCodeAndEntityResponse(status, headers, _)))
          if (status.isSuccess)
            // for 2xx status codes delegate content-type negotiation to the value marshaller
            mappedMarshallings
          else
            // For non-2xx status, add an opaque fallback marshalling using the first returned marshalling
            // that will be used if the result wouldn't otherwise be accepted.
            //
            // The reasoning is that users often use routes like  `complete(400 -> "Illegal request in this context")`
            // to eagerly complete a route with an error (instead of using rejection handling). If the client uses
            // narrow Accept headers like `Accept: application/json` the user supplied error message would not be
            // rendered because it will only accept `text/plain` but not `application/json` responses and fail the
            // whole request with a "406 Not Acceptable" response.
            //
            // Adding the opaque fallback rendering will give an escape hatch for those situations.
            // See akka/akka#19397, akka/akka#19842, and #1072.
            mappedMarshallings match {
              case Nil                   => Nil
              case firstMarshalling :: _ => mappedMarshallings :+ firstMarshalling.toOpaque(HttpCharsets.`UTF-8`)
            }
        }
    })

  // The null ClassTag being passed to fromEntityStreamingSupportAndByteStringMarshaller is safe,
  // as it is handled gracefully by NoStrictlyCompatibleElementMarshallingAvailableException.
  @deprecated("This method exists only for the purpose of binary compatibility, it used to be implicit.", "10.1.0")
  private[akka] def fromEntityStreamingSupportAndByteStringMarshaller[T, M](s: EntityStreamingSupport, m: ToByteStringMarshaller[T]): ToResponseMarshaller[Source[T, M]] =
    fromEntityStreamingSupportAndByteStringMarshaller(null, s, m)

  implicit def fromEntityStreamingSupportAndByteStringMarshaller[T: ClassTag, M](implicit s: EntityStreamingSupport, m: ToByteStringMarshaller[T]): ToResponseMarshaller[Source[T, M]] =
    fromEntityStreamingSupportAndByteStringSourceMarshaller(s, m.map(Source.single))
}

trait LowPriorityToResponseMarshallerImplicits {
  implicit def liftMarshallerConversion[T](m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    liftMarshaller(m)
  implicit def liftMarshaller[T](implicit m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    PredefinedToResponseMarshallers.fromToEntityMarshaller()

  implicit def fromEntityStreamingSupportAndEntityMarshaller[T, M](implicit s: EntityStreamingSupport, m: ToEntityMarshaller[T], tag: ClassTag[T]): ToResponseMarshaller[Source[T, M]] =
    fromEntityStreamingSupportAndByteStringSourceMarshaller[T, M](s, m.map(_.dataBytes))

  private[marshalling] def fromEntityStreamingSupportAndByteStringSourceMarshaller[T: ClassTag, M](s: EntityStreamingSupport, m: Marshaller[T, Source[ByteString, _]]): ToResponseMarshaller[Source[T, M]] = {
    Marshaller[Source[T, M], HttpResponse] { implicit ec => source =>
      FastFuture successful {
        Marshalling.WithFixedContentType(s.contentType, () => {
          val availableMarshallingsPerElement = source.mapAsync(1) { t => m(t)(ec) }

          val bestMarshallingPerElement = availableMarshallingsPerElement map { marshallings =>
            // pick the Marshalling that matches our EntityStreamingSupport
            // TODO we could either special case for certain known types,
            // or extend the entity support to be more advanced such that it would negotiate the element content type it
            // is able to render.
            selectMarshallingForContentType(marshallings, s.contentType)
              .orElse {
                marshallings collectFirst { case Marshalling.Opaque(marshal) => marshal }
              }
              .getOrElse(throw new NoStrictlyCompatibleElementMarshallingAvailableException[T](s.contentType, marshallings))
          }

          val marshalledElements: Source[ByteString, M] =
            bestMarshallingPerElement
              .flatMapConcat(_.apply()) // marshal!
              .via(s.framingRenderer)

          HttpResponse(entity = HttpEntity(s.contentType, marshalledElements))
        }) :: Nil
      }
    }
  }
}

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers {
  /** INTERNAL API */
  @InternalApi
  private def statusCodeResponse(statusCode: StatusCode, headers: immutable.Seq[HttpHeader] = Nil): HttpResponse = {
    val entity =
      if (statusCode.allowsEntity) HttpEntity(statusCode.defaultMessage)
      else HttpEntity.Empty

    HttpResponse(status = statusCode, headers = headers, entity = entity)
  }

  private def statusCodeAndEntityResponse(statusCode: StatusCode, headers: immutable.Seq[HttpHeader], entity: ResponseEntity): HttpResponse = {
    if (statusCode.allowsEntity) HttpResponse(statusCode, headers, entity)
    else HttpResponse(statusCode, headers, HttpEntity.Empty)
  }
}

final class NoStrictlyCompatibleElementMarshallingAvailableException[T](
  streamContentType:     ContentType,
  availableMarshallings: List[Marshalling[_]])(implicit tag: ClassTag[T])
  extends RuntimeException(
    s"None of the available marshallings ($availableMarshallings) directly " +
      s"match the ContentType requested by the top-level streamed entity ($streamContentType). " +
      s"Please provide an implicit `Marshaller[${if (tag == null) "T" else tag.runtimeClass.getName}, HttpEntity]` " +
      s"that can render ${if (tag == null) "" else tag.runtimeClass.getName + " "}" +
      s"as [$streamContentType]")
