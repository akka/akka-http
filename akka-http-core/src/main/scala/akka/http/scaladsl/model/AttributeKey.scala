/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.javadsl.{ model => jm }

class AttributeKey[T] extends jm.AttributeKey[T]
object AttributeKey {
  def apply[T](): AttributeKey[T] = new AttributeKey[T]()
}
