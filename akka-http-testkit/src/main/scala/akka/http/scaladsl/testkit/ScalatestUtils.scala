/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import org.scalatest.Suite
import org.scalatest.matchers.Matcher

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Try

trait ScalatestUtils extends MarshallingTestUtils {
  import org.scalatest.Matchers._

  def evaluateTo[T](value: T): Matcher[Future[T]] =
    equal(value).matcher[T] compose (x => Await.result(x, marshallingTimeout))

  def haveFailedWith(t: Throwable): Matcher[Future[_]] =
    equal(t).matcher[Throwable] compose (x => Await.result(x.failed, marshallingTimeout))

  def unmarshalToValue[T: FromEntityUnmarshaller](value: T)(implicit ec: ExecutionContext, mat: Materializer): Matcher[HttpEntity] =
    equal(value).matcher[T] compose (unmarshalValue(_))

  def unmarshalTo[T: FromEntityUnmarshaller](value: Try[T])(implicit ec: ExecutionContext, mat: Materializer): Matcher[HttpEntity] =
    equal(value).matcher[Try[T]] compose (unmarshal(_))
}

trait ScalatestRouteTest extends RouteTest with TestFrameworkInterface.Scalatest with ScalatestUtils { this: Suite => }
