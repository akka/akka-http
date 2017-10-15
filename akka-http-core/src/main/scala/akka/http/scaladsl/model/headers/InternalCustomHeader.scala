/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.annotation.InternalApi

/**
  * INTERNAL API
  */
@InternalApi
private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  final def renderInRequests = false
  final def renderInResponses = false
  def value: String = ""
}
