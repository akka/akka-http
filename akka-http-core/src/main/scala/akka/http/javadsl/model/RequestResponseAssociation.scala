/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model

import java.util.concurrent.CompletableFuture

/**
 * A marker trait for attribute values that should be (automatically) carried over from request to response.
 */
trait RequestResponseAssociation

/**
 * An association for completing a future when the response arrives.
 */
final class ResponseFuture(val future: CompletableFuture[HttpResponse]) extends akka.http.scaladsl.model.RequestResponseAssociation
object ResponseFuture {
  val KEY = AttributeKey.create[ResponseFuture]("association-future-handle", classOf[ResponseFuture])
  def apply(promise: CompletableFuture[HttpResponse]): ResponseFuture = new ResponseFuture(promise)
}
