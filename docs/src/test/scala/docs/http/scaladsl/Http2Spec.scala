/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl

//#bindAndHandleAsync
import akka.http.scaladsl.Http2
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }

//#bindAndHandleAsync

val asyncHandler = ???
val httpsServerContext = ???

//#bindAndHandleAsync
Http2().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 8443, httpsServerContext)
//#bindAndHandleAsync
