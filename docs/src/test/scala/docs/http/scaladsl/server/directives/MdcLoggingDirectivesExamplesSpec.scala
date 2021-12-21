package docs.http.scaladsl.server.directives

import akka.event.{ LogMarker, MarkerLoggingAdapter }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class MdcLoggingDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "withMdcLogging" in {
    //#withMdcLogging
    withMdcLogging {
      extractLog {
        case m: MarkerLoggingAdapter =>
          // Logger will be a MarkerLoggingAdapter.
          // Log will include entries for "user_id" and "request_id".
          m.info(LogMarker("", Map("user_id" -> "1234", "request_id" -> "abcd")), "completing request")
          complete(StatusCodes.OK)
      }
    }
    //#withMdcLogging
  }

  "withMdcEntries" in {
    //#withMdcEntries
    withMdcEntries(("user_id", "1234"), ("request_id", "abcd")) {
      extractLog { log =>
        // Log will include entries for "user_id" and "request_id".
        log.info("completing request")
        complete(StatusCodes.OK)
      }
    }
    //#withMdcEntries
  }

  "withMdcEntry" in {
    //#withMdcEntry
    withMdcEntry("user_id", "1234") {
      withMdcEntry("request_id", "abcd") {
        extractLog { log =>
          // Log will include entries for "user_id" and "request_id".
          log.info("completing request")
          complete(StatusCodes.OK)
        }
      }
    }
    //#withMdcEntry
  }

  "extractMarkerLog" in {
    //#extractMarkerLog
    extractMarkerLog { m: MarkerLoggingAdapter =>
      // Log will include entires for "user_id" and "request_id".
      m.info(LogMarker("", Map("user_id" -> "1234", "request_id" -> "abcd")), "completing request")
      complete(StatusCodes.OK)
    }
    //#extractMarkerLog
  }
}
