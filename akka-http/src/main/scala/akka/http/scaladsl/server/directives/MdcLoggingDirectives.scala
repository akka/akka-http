package akka.http.scaladsl.server.directives

import akka.event.{
  DefaultLoggingFilter,
  DiagnosticMarkerBusLoggingAdapter,
  LogSource,
  MarkerLoggingAdapter
}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._

/**
 * @groupname mdc Mdc logging directives
 * @groupprio mdc 240
 */
trait MdcLoggingDirectives {

  import MdcLoggingDirectives.extractDiagnosticMarkerLog

  def withMdcLogging: Directive0 =
    extractDiagnosticMarkerLog
      .flatMap { dmbla: DiagnosticMarkerBusLoggingAdapter =>
        mapRequestContext { ctx =>
          ctx.withLog(dmbla)
        }
      }

  def extractMarkerLog: Directive1[MarkerLoggingAdapter] =
    (withMdcLogging & extractDiagnosticMarkerLog).map(identity[MarkerLoggingAdapter])

  def withMdcEntries(entries: (String, Any)*): Directive0 =
    (withMdcLogging & extractDiagnosticMarkerLog).map(log => log.mdc(log.mdc ++ entries.toMap))

  def withMdcEntry(key: String, value: Any): Directive0 =
    withMdcEntries((key, value))
}

object MdcLoggingDirectives extends MdcLoggingDirectives {
  private val extractDiagnosticMarkerLog: Directive1[DiagnosticMarkerBusLoggingAdapter] =
    extractActorSystem
      .flatMap { sys =>
        extractRequestContext
          .flatMap { ctx =>
            provide {
              ctx.log match {
                // If it's already a DiagnosticMarkerBusLoggingAdapter, this is a no-op.
                case dmbla: DiagnosticMarkerBusLoggingAdapter => dmbla
                // Otherwise, we need to construct a DiagnosticLoggingAdapter.
                case _ =>
                  val (str, cls) = LogSource.fromAnyRef(sys, sys)
                  val filter = new DefaultLoggingFilter(sys.settings, sys.eventStream)
                  new DiagnosticMarkerBusLoggingAdapter(sys.eventStream, str, cls, filter)
              }
            }
          }
      }
}
