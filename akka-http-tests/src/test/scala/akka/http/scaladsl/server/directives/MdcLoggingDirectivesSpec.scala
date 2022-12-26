package akka.http.scaladsl.server.directives

import akka.event._
import akka.http.scaladsl.server.RoutingSpec
import akka.testkit.EventFilter

import scala.collection.mutable.ListBuffer

class MdcLoggingDirectivesSpec extends RoutingSpec {

  "The `withMarkerLoggingAdapter` directive" should {
    "set a DiagnosticMarkerBusLoggingAdapter on the request context and provide the same adapter to the caller" in {
      Get() ~> withMarkerLoggingAdapter { provided: MarkerLoggingAdapter =>
        extractLog { extracted: LoggingAdapter =>
          extracted shouldEqual provided
          completeOk
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
    "provides a new DiagnosticMarkerBusLoggingAdapter for each invocation" in {
      Get() ~> withMarkerLoggingAdapter { _ =>
        extractLog { log1 =>
          withMarkerLoggingAdapter { _ =>
            extractLog { log2 =>
              log1 should not equal log2
              completeOk
            }
          }
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
  }

  "The `withMdcEntries` and `withMdcEntry` directives" should {
    "incrementally append entries to the LoggingAdapter MDC maps" in {
      Get() ~> extractLog { log1 =>
        withMdcEntry("foo", "foo entry 1") {
          extractLog { log2 =>
            withMdcEntries("foo" -> "foo entry 2", "bar" -> "bar entry 1") {
              extractLog { log3 =>
                log1.mdc shouldBe Map.empty
                log2.mdc shouldBe Map("foo" -> "foo entry 1")
                log3.mdc shouldBe Map("foo" -> "foo entry 2", "bar" -> "bar entry 1")
                completeOk
              }
            }
          }
        }
      } ~> check {
        response shouldEqual Ok
      }
    }
    "include the entries in the LoggingEvents" in {
      val buf = ListBuffer.empty[Logging.Info2]
      val filter = EventFilter.custom {
        case e: Logging.Info2 => buf.append(e).nonEmpty
        case _                => false
      }
      filter.intercept {
        Get() ~> withMdcEntries("user_id" -> "1234", "request_id" -> "abcd") {
          extractLog { log1 =>
            log1.info("test 1")
            withMdcEntry("status", "200") {
              extractLog { log2 =>
                log1.info("test 2")
                log2.info("test 3")
                completeOk
              }
            }
          }
        } ~> check {
          response shouldEqual Ok
          buf.size shouldBe 3
          val l1 = buf(0)
          val l2 = buf(1)
          val l3 = buf(2)
          l1.message shouldBe "test 1"
          l1.mdc shouldBe Map("user_id" -> "1234", "request_id" -> "abcd")
          l2.message shouldBe "test 2"
          l2.mdc shouldBe Map("user_id" -> "1234", "request_id" -> "abcd")
          l3.message shouldBe "test 3"
          l3.mdc shouldBe Map("user_id" -> "1234", "request_id" -> "abcd", "status" -> "200")
        }
      }
    }
  }
}
