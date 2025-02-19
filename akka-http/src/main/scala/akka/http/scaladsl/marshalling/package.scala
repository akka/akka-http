/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import scala.collection.immutable
import akka.http.scaladsl.model._
import akka.util.ByteString

package object marshalling {
  //#marshaller-aliases
  type ToEntityMarshaller[T] = Marshaller[T, MessageEntity]
  type ToByteStringMarshaller[T] = Marshaller[T, ByteString]
  type ToHeadersAndEntityMarshaller[T] = Marshaller[T, (immutable.Seq[HttpHeader], MessageEntity)]
  type ToResponseMarshaller[T] = Marshaller[T, HttpResponse]
  type ToRequestMarshaller[T] = Marshaller[T, HttpRequest]
  //#marshaller-aliases
}
