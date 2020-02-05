package akka.http.scaladsl.model

import akka.http.javadsl.{ model => jm }

class AttributeKey[T] extends jm.AttributeKey[T]
object AttributeKey {
  def apply[T](): AttributeKey[T] = new AttributeKey[T]()
}
