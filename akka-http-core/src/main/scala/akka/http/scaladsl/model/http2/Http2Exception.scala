/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.http2

import akka.annotation.ApiMayChange
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode

import scala.util.control.NoStackTrace

/**
 * Base class for HTTP2 exceptions.
 */
@ApiMayChange
class Http2Exception(msg: String) extends RuntimeException(msg)

/**
 * Exception that will be reported on the request entity stream when the peer closed the stream.
 */
@ApiMayChange
class PeerClosedStreamException(val streamId: Int, val errorCode: String, val numericErrorCode: Int)
  extends Http2Exception(f"Stream with ID [$streamId%d] was closed by peer with code $errorCode%s(0x$numericErrorCode%02x)") with NoStackTrace {
  private[http] def this(streamId: Int, errorCode: ErrorCode) = this(streamId, errorCode.toString, errorCode.id)
}
