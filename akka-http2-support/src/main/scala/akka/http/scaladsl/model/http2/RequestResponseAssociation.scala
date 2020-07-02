/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.http2

/**
 * A marker trait for attribute values that should be (automatically) carried over from request to response.
 */
trait RequestResponseAssociation
final case class SimpleRequestResponseAttribute[T](value: T) extends RequestResponseAssociation
