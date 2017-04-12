/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.collection.immutable
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture._

trait PredefinedToRequestMarshallers {
  private type TRM[T] = ToRequestMarshaller[T] // brevity alias

  implicit val fromRequest: TRM[HttpRequest] = Marshaller.opaque(identity)

  implicit def fromUri: TRM[Uri] =
    Marshaller.opaque(uri ⇒ HttpRequest(uri = uri))

  implicit def fromMethodAndUriAndValue[S, T](implicit mt: ToEntityMarshaller[T]): TRM[(HttpMethod, Uri, T)] =
    fromMethodAndUriAndHeadersAndValue[T] compose { case (m, u, v) ⇒ (m, u, Nil, v) }

  implicit def fromMethodAndUriAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(HttpMethod, Uri, immutable.Seq[HttpHeader], T)] =
    mt.wrapInAndOut {
      case (m, u, h, v) ⇒ (v, HttpRequest(m, u, h, _))
    }
}

object PredefinedToRequestMarshallers extends PredefinedToRequestMarshallers
