/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.unmarshalling

import akka.http.scaladsl.model.{ ContentType, ContentTypeRange }

/**
 * Signals that unmarshalling failed because the entity content-type did not match one of the supported ranges.
 * This error cannot be thrown by custom code, you need to use the `forContentTypes` modifier on a base
 * [[akka.http.scaladsl.unmarshalling.Unmarshaller]] instead.
 */
final case class UnsupportedContentTypeException(
  supported:   Set[ContentTypeRange],
  contentType: Option[ContentType]   = None)
  extends RuntimeException(supported.mkString(
    s"Unsupported Content-Type${contentType.map(": " + _).getOrElse("")}, supported: ", ", ", "")) {

  //constructors and copy methods added to cover binary compatibility
  def this(supported: Set[ContentTypeRange]) = this(supported, None)

  def this(contentType: Option[ContentType], supported: Set[ContentTypeRange]) = this(supported, contentType)

  def copy(supported: Set[ContentTypeRange]) = UnsupportedContentTypeException(supported, contentType)

  def copy(supported: Set[ContentTypeRange], contentType: Option[ContentType] = None) =
    UnsupportedContentTypeException(supported, contentType)
}

object UnsupportedContentTypeException {
  def apply(supported: ContentTypeRange*): UnsupportedContentTypeException =
    UnsupportedContentTypeException(Set(supported: _*))

  def apply(contentType: Option[ContentType], supported: ContentTypeRange*): UnsupportedContentTypeException =
    UnsupportedContentTypeException(Set(supported: _*), contentType)

  def apply(supported: Set[ContentTypeRange]): UnsupportedContentTypeException =
    UnsupportedContentTypeException(supported)
}
