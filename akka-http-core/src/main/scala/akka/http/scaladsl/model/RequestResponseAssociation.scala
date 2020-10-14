/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.annotation.ApiMayChange

/**
 * A marker trait for attribute values that should be (automatically) carried over from request to response.
 */
@ApiMayChange
trait RequestResponseAssociation

/**
 * A simple value holder class implementing RequestResponseAssociation.
 */
@ApiMayChange
final case class SimpleRequestResponseAttribute[T](value: T) extends RequestResponseAssociation
