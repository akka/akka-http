package docs.http.scaladsl.server.directives

import akka.event._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class MdcLoggingDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "withMarkerLoggingAdapter" in {
    //#withMarkerLoggingAdapter
    withMarkerLoggingAdapter { m: MarkerLoggingAdapter =>
      // This log.info includes entries for "user_id" and "request_id",
      // but subsequent logging calls will not include them.
      val marker = LogMarker("", Map("user_id" -> "1234", "request_id" -> "abcd"))
      m.info(marker, "completing request")
      complete(StatusCodes.OK)
    }
    //#withMarkerLoggingAdapter
  }

  "withMdcEntries" in {
    //#withMdcEntries
    withMdcEntries(("user_id", "1234"), ("request_id", "abcd")) {
      extractLog { log =>
        // This log.info includes entries for "user_id" and "request_id",
        // and subsequent calls will also include them.
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
          // This log.info includes entries for "user_id" and "request_id",
          // and subsequent calls will also include them.
          log.info("completing request")
          complete(StatusCodes.OK)
        }
      }
    }
    //#withMdcEntry
  }
}
