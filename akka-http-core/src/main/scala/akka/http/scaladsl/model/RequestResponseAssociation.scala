/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.annotation.ApiMayChange
import scala.concurrent.Promise

/**
 * A marker trait for attribute values that should be (automatically) carried over from request to response.
 */
@ApiMayChange
trait RequestResponseAssociation

/**
 * A simple value holder class implementing RequestResponseAssociation.
 */
@ApiMayChange
case class SimpleRequestResponseAttribute[T](value: T) extends RequestResponseAssociation

/**
 * An association for completing a future when the response arrives.
 */
final class ResponseFuture(val promise: Promise[HttpResponse]) extends RequestResponseAssociation
object ResponseFuture {
  val Key = AttributeKey[ResponseFuture]("association-future-handle")
  def apply(promise: Promise[HttpResponse]): ResponseFuture = new ResponseFuture(promise)
}

