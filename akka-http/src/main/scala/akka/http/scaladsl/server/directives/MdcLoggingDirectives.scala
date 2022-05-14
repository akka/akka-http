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
              ctx.log match {
                // In order to avoid race conditions from using a shared LoggingAdapter, even if the existing adapter
                // is a DiagnosticMarkerBusLoggingAdapter, we still create a new one and merge the two entries.
                case existingAdapter: DiagnosticMarkerBusLoggingAdapter =>
                  import existingAdapter._
                  val newAdapter = new DiagnosticMarkerBusLoggingAdapter(bus, logSource, logClass, loggingFilterWithMarker)
                  newAdapter.mdc(existingAdapter.mdc ++ entries)
                  newAdapter
                // If the logger is not already a DiagnosticLoggingAdapter, we create a new one with the given entries.
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
