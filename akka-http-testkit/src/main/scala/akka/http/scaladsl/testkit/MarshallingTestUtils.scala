/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshal }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse, MediaRange }
import akka.stream.Materializer

import scala.util.Try

trait MarshallingTestUtils {
  def marshal[T: ToEntityMarshaller](value: T)(implicit ec: ExecutionContext, mat: Materializer): HttpEntity.Strict =
    Await.result(Marshal(value).to[HttpEntity].flatMap(_.toStrict(1.second)), 2.second)

  def marshalToResponseForRequestAccepting[T: ToResponseMarshaller](value: T, mediaRanges: MediaRange*)(implicit ec: ExecutionContext): HttpResponse =
    marshalToResponse(value, HttpRequest(headers = Accept(mediaRanges: _*) :: Nil))

  def marshalToResponse[T: ToResponseMarshaller](value: T, request: HttpRequest = HttpRequest())(implicit ec: ExecutionContext): HttpResponse =
    Await.result(Marshal(value).toResponseFor(request), 1.second)

  def unmarshalValue[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): T =
    unmarshal(entity).get

  def unmarshal[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): Try[T] = {
    val fut = Unmarshal(entity).to[T]
    Await.ready(fut, 1.second)
    fut.value.get
  }
}

