/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import java.io.File
import java.nio.file.Path

import JavaMapping.Implicits._
import akka.annotation.InternalApi
import akka.http.javadsl.model._
import akka.http.scaladsl.model

/**
 *  INTERNAL API
 *
 *  Accessors for constructors with default arguments to be used from the Java implementation
 */
@InternalApi
private[http] object JavaAccessors {
  /** INTERNAL API */
  def HttpRequest(): HttpRequest = model.HttpRequest()

  /** INTERNAL API */
  def HttpRequest(uri: String): HttpRequest = model.HttpRequest(uri = uri)

  /** INTERNAL API */
  def HttpResponse(): HttpResponse = model.HttpResponse()

  /** INTERNAL API */
  def HttpEntity(contentType: ContentType, file: File): UniversalEntity =
    model.HttpEntity.fromPath(contentType.asScala, file.toPath)

  /** INTERNAL API */
  def HttpEntity(contentType: ContentType, file: Path): UniversalEntity =
    model.HttpEntity.fromPath(contentType.asScala, file)
}
