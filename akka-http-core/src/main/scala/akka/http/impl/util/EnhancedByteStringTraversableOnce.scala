/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi
import akka.http.ccompat._
import akka.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedByteStringTraversableOnce(val byteStrings: IterableOnce[ByteString]) extends AnyVal {
  def join: ByteString = byteStrings.foldLeft(ByteString.empty)(_ ++ _)
}
