/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

class HttpIdleTimeoutException(msg: String, timeout: FiniteDuration) extends TimeoutException(msg: String) with NoStackTrace
