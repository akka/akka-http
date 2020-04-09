/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

import akka.event.LoggingAdapter
import akka.http.javadsl.{ model => jm }
import akka.http.scaladsl.model.{ ErrorInfo, HttpResponse, StatusCode }
import akka.http.scaladsl.settings.ServerSettings

abstract class ParsingErrorHandler {
  def handle(status: StatusCode, error: ErrorInfo, log: LoggingAdapter, settings: ServerSettings): jm.HttpResponse
}

object DefaultParsingErrorHandler extends ParsingErrorHandler {
  import akka.http.impl.engine.parsing.logParsingError

  override def handle(status: StatusCode, info: ErrorInfo, log: LoggingAdapter, settings: ServerSettings): HttpResponse = {
    logParsingError(
      info withSummaryPrepended s"Illegal request, responding with status '$status'",
      log, settings.parserSettings.errorLoggingVerbosity)
    val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
    HttpResponse(status, entity = msg)
  }
}
