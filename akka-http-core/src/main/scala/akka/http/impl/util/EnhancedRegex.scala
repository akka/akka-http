/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.util

import akka.annotation.InternalApi

import scala.util.matching.Regex

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedRegex(val regex: Regex) extends AnyVal {
  def groupCount = regex.pattern.matcher("").groupCount()
}
