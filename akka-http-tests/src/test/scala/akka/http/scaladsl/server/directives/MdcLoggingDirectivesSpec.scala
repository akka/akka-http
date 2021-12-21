package akka.http.scaladsl.server.directives

import akka.event.{ DiagnosticMarkerBusLoggingAdapter, MarkerLoggingAdapter }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.RoutingSpec
import scala.jdk.CollectionConverters._

class MdcLoggingDirectivesSpec extends RoutingSpec {

  "The `withMdcLogging` directive" should {
    "provide a DiagnosticMarkerBusLoggingAdapter" in {
      Get() ~> withMdcLogging {
        extractLog {
          case _: DiagnosticMarkerBusLoggingAdapter => completeOk
          case other                                => failTest(s"expected a DiagnosticMarkerBusLoggingAdapter but found $other")
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
    "provide a new DiagnosticMarkerBusLoggingAdapter for each request" in {
      val route = withMdcLogging {
        extractLog {
          case l: DiagnosticMarkerBusLoggingAdapter => complete(l.hashCode().toString)
          case other                                => failTest(s"expected a DiagnosticMarkerBusLoggingAdapter but found $other")
        }
      }
      val reps = 100
      val responseEntities = (1 to reps).map(_ => Get() ~> route ~> check {
        status shouldEqual StatusCodes.OK
        entityAs[String]
      })
      responseEntities.distinct.length shouldBe reps
    }
    "provide the same DiagnosticMarkerBusLoggingAdapter when nested multiple times" in {
      Get() ~> withMdcLogging {
        extractLog { log1 =>
          withMdcLogging {
            extractLog { log2 =>
              if (log1 == log2) completeOk
              else failTest(s"$log1 != $log2")
            }
          }
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
  }

  "The `extractMarkerLog` directive" should {
    "provide a MarkerLoggingAdapter" in {
      Get() ~> extractMarkerLog {
        case _: MarkerLoggingAdapter => completeOk
        case other                   => failTest(s"expected a MarkerLoggingAdapter but found $other")
      }
    }
    "provide a new MarkerLoggingAdapter for each request" in {
      val route = extractMarkerLog {
        case log: MarkerLoggingAdapter => complete(log.hashCode().toString)
        case other                     => failTest(s"expected a MarkerLoggingAdapter but found $other")
      }
      val reps = 100
      val responseEntities = (1 to reps).map(_ => Get() ~> route ~> check {
        status shouldEqual StatusCodes.OK
        entityAs[String]
      })
      responseEntities.distinct.length shouldBe reps
    }
    "provide the same MarkerLoggingAdapter when nested multiple times" in {
      Get() ~> extractMarkerLog { log1 =>
        withMdcLogging {
          extractMarkerLog { log2 =>
            if (log1 == log2) completeOk
            else failTest(s"$log1 != $log2")
          }
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
  }

  "The `withMdcEntries` directive" should {
    "append entries to the DiagnosticMarkerBusLoggingAdapter's MDC map" in {
      Get() ~> withMdcEntries(("foo", "foo entry"), ("bar", "bar entry")) {
        extractMarkerLog {
          case log: DiagnosticMarkerBusLoggingAdapter =>
            val map = log.getMDC.asScala
            if (!map.get("foo").contains("foo entry")) failTest(s"missing or incorrect key 'foo' in $map")
            else if (!map.get("bar").contains("bar entry")) failTest(s"missing or incorrect key 'bar' in $map")
            else completeOk
          case other => failTest(s"expected a DiagnosticMarkerBusLoggingAdapter but found $other")
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
    "replace entries with same key when nested" in {
      Get() ~> withMdcEntries(("foo", "foo entry 1")) {
        extractMarkerLog {
          case log: DiagnosticMarkerBusLoggingAdapter =>
            val map = log.getMDC.asScala
            if (!map.get("foo").contains("foo entry 1")) failTest(s"'foo' should be 'foo entry 1'")
            else withMdcEntries(("foo", "foo entry 2")) {
              val map = log.getMDC.asScala
              if (!map.get("foo").contains("foo entry 2")) failTest(s"'foo' should be 'foo entry 2'")
              else completeOk
            }
          case other => failTest(s"expected a DiagnosticMarkerBusLoggingAdapter but found $other")
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
  }
}
