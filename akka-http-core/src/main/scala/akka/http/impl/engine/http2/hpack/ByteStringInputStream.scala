/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.{ ByteArrayInputStream, InputStream }

import akka.annotation.InternalApi
import akka.util.ByteString
import akka.util.ByteString.ByteString1C

/** INTERNAL API */
@InternalApi
private[http2] object ByteStringInputStream {

  def apply(bs: ByteString): InputStream =
    bs match {
      case cs: ByteString1C =>
        // TODO optimise, ByteString needs to expose InputStream (esp if array backed, nice!)
        new ByteArrayInputStream(cs.toArray)
      case _ =>
        // NOTE: We actually measured recently, and compact + use array was pretty good usually
        apply(bs.compact)
    }
}
