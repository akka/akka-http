package akka.http.scaladsl.server.directives

import akka.event._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._

/**
 * @groupname mdc Mdc logging directives
 * @groupprio mdc 240
 */
trait MdcLoggingDirectives {

  import MdcLoggingDirectives.createDiagnosticMarkerLoggingAdapter

  def withMarkerLoggingAdapter: Directive1[MarkerLoggingAdapter] = {
    createDiagnosticMarkerLoggingAdapter(Map.empty)
      .flatMap { dmbla: DiagnosticMarkerBusLoggingAdapter =>
        mapRequestContext { ctx =>
          ctx.withLog(dmbla)
        } & provide {
          dmbla: MarkerLoggingAdapter
        }
      }
  }

  def withMdcEntries(mdc: (String, Any)*): Directive0 =
    createDiagnosticMarkerLoggingAdapter(mdc.toMap)
      .flatMap { dmbla: DiagnosticMarkerBusLoggingAdapter =>
        mapRequestContext { ctx =>
          ctx.withLog(dmbla)
        }
      }

  def withMdcEntry(key: String, value: Any): Directive0 =
    withMdcEntries((key, value))
}

object MdcLoggingDirectives extends MdcLoggingDirectives {
  private def createDiagnosticMarkerLoggingAdapter(entries: Map[String, Any]): Directive1[DiagnosticMarkerBusLoggingAdapter] =
    extractActorSystem
      .flatMap { sys =>
        extractRequestContext
          .flatMap { ctx =>
            provide {
              // Convert the existing ctx.log LoggingAdapter to a DiagnosticMarkerBusLoggingAdapter.
              // This requires various amounts of effort depending on the type of the current LoggingAdapter.
              // In all cases, we create a _new_ DiagnosticMarkerBusLoggingAdapter in order to avoid race conditions,
              // where multiple requests could add/remove entries from the same MDC map.
              ctx.log match {
                case existingAdapter: DiagnosticMarkerBusLoggingAdapter =>
                  import existingAdapter._
                  val newAdapter = new DiagnosticMarkerBusLoggingAdapter(bus, logSource, logClass, loggingFilterWithMarker)
                  newAdapter.mdc(existingAdapter.mdc ++ entries)
                  newAdapter
                case bla: BusLogging =>
                  val filter = new DefaultLoggingFilter(() => bla.bus.logLevel)
                  val newAdapter = new DiagnosticMarkerBusLoggingAdapter(bla.bus, bla.logSource, bla.logClass, filter)
                  newAdapter.mdc(entries)
                  newAdapter
                case _ =>
                  val (str, cls) = LogSource.fromAnyRef(sys, sys)
                  val filter = new DefaultLoggingFilter(sys.settings, sys.eventStream)
                  val newAdapter = new DiagnosticMarkerBusLoggingAdapter(sys.eventStream, str, cls, filter)
                  newAdapter.mdc(entries)
                  newAdapter
              }
            }
          }
      }
}
