/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.concurrent.{ ExecutionContext, Future }
import akka.http.ccompat._
import akka.http.scaladsl.server.ContentNegotiator
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture._

import scala.util.control.NoStackTrace

object Marshal {
  def apply[T](value: T): Marshal[T] = new Marshal(value)

  final case class UnacceptableResponseContentTypeException(supported: Set[ContentNegotiator.Alternative])
    extends RuntimeException with NoStackTrace

  private[marshalling] def selectMarshallingForContentType[T](marshallings: Seq[Marshalling[T]], contentType: ContentType): Option[() ⇒ T] = {
    contentType match {
      case _: ContentType.Binary | _: ContentType.WithFixedCharset | _: ContentType.WithMissingCharset ⇒
        marshallings collectFirst { case Marshalling.WithFixedContentType(`contentType`, marshal) ⇒ marshal }
      case ContentType.WithCharset(mediaType, charset) ⇒
        marshallings collectFirst {
          case Marshalling.WithFixedContentType(`contentType`, marshal) ⇒ marshal
          case Marshalling.WithOpenCharset(`mediaType`, marshal)        ⇒ () ⇒ marshal(charset)
        }
    }
  }
}

class Marshal[A](val value: A) {
  /**
   * Marshals `value` using the first available [[Marshalling]] for `A` and `B` provided by the given [[Marshaller]].
   * If the marshalling is flexible with regard to the used charset `UTF-8` is chosen.
   */
  def to[B](implicit m: Marshaller[A, B], ec: ExecutionContext): Future[B] =
    m(value).fast.map {
      _.head match {
        case Marshalling.WithFixedContentType(_, marshal) ⇒ marshal()
        case Marshalling.WithOpenCharset(_, marshal)      ⇒ marshal(HttpCharsets.`UTF-8`)
        case Marshalling.Opaque(marshal)                  ⇒ marshal()
      }
    }

  /**
   * Marshals `value` to an `HttpResponse` for the given `HttpRequest` with full content-negotiation.
   */
  def toResponseFor(request: HttpRequest)(implicit m: ToResponseMarshaller[A], ec: ExecutionContext): Future[HttpResponse] = {
    import akka.http.scaladsl.marshalling.Marshal._
    val ctn = ContentNegotiator(request.headers)

    m(value).fast.map { marshallings ⇒
      val supportedAlternatives: List[ContentNegotiator.Alternative] =
        marshallings.iterator.collect {
          case Marshalling.WithFixedContentType(ct, _) ⇒ ContentNegotiator.Alternative(ct)
          case Marshalling.WithOpenCharset(mt, _)      ⇒ ContentNegotiator.Alternative(mt)
        }.to(scala.collection.immutable.List)
      val bestMarshal = {
        if (supportedAlternatives.nonEmpty) {
          ctn.pickContentType(supportedAlternatives)
            .flatMap(selectMarshallingForContentType(marshallings, _))
        } else None
      } orElse {
        marshallings collectFirst { case Marshalling.Opaque(marshal) ⇒ marshal }
      } getOrElse {
        throw UnacceptableResponseContentTypeException(supportedAlternatives.toSet)
      }
      bestMarshal()
    }
  }
}
