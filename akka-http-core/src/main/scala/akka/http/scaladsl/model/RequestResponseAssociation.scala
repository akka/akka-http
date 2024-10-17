/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.annotation.InternalStableApi

import scala.concurrent.Promise

/**
 * A marker trait for attribute values that should be (automatically) carried over from request to response.
 */
@InternalStableApi
trait RequestResponseAssociation extends akka.http.javadsl.model.RequestResponseAssociation

/**
 * A simple value holder class implementing RequestResponseAssociation.
 */
final case class SimpleRequestResponseAttribute[T](value: T) extends RequestResponseAssociation

/**
 * An association for completing a future when the response arrives.
 */
final class ResponsePromise(val promise: Promise[HttpResponse]) extends RequestResponseAssociation
object ResponsePromise {
  val Key = AttributeKey[ResponsePromise]("association-future-handle")
  def apply(promise: Promise[HttpResponse]): ResponsePromise = new ResponsePromise(promise)
}

