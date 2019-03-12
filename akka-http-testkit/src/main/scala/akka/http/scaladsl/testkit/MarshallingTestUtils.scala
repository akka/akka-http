/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.impl.util._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse, MediaRange }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshal }
import akka.stream.Materializer
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.util.Try

trait MarshallingTestUtils {

  def testConfig: Config

  def marshallingTimeout = testConfig.getFiniteDuration("akka.http.testkit.marshalling.timeout")

  def marshal[T: ToEntityMarshaller](value: T)(implicit ec: ExecutionContext, mat: Materializer): HttpEntity.Strict =
    Await.result(Marshal(value).to[HttpEntity].flatMap(_.toStrict(marshallingTimeout)), 2 * marshallingTimeout)

  def marshalToResponseForRequestAccepting[T: ToResponseMarshaller](value: T, mediaRanges: MediaRange*)(implicit ec: ExecutionContext): HttpResponse =
    marshalToResponse(value, HttpRequest(headers = Accept(mediaRanges.toList) :: Nil))

  def marshalToResponse[T: ToResponseMarshaller](value: T, request: HttpRequest = HttpRequest())(implicit ec: ExecutionContext): HttpResponse =
    Await.result(Marshal(value).toResponseFor(request), marshallingTimeout)

  def unmarshalValue[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): T =
    unmarshal(entity).get

  def unmarshal[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): Try[T] = {
    val fut = Unmarshal(entity).to[T]
    Await.ready(fut, marshallingTimeout)
    fut.value.get
  }
}

