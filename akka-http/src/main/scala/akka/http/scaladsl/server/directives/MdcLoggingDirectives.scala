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

  def withMdcLogging: Directive0 =
    extractActorSystem
      .flatMap { sys =>
        mapRequestContext { ctx =>
          ctx.log match {
            // If it's already a DiagnosticMarkerBusLoggingAdapter, this is a no-op.
            case _: DiagnosticMarkerBusLoggingAdapter => ctx
            // Otherwise, we need to give the context a DiagnosticLoggingAdapter.
            case _ =>
              val (str, cls) = LogSource.fromAnyRef(sys, sys)
              val loggingFilter =
                new DefaultLoggingFilter(sys.settings, sys.eventStream)
              val dmbla = new DiagnosticMarkerBusLoggingAdapter(
                sys.eventStream,
                str,
                cls,
                loggingFilter)
              ctx.withLog(dmbla)
          }
        }
      }

  def extractMarkerLog: Directive1[MarkerLoggingAdapter] =
    MdcLoggingDirectives.extractDiagnosticMarkerLog
      .map(identity[MarkerLoggingAdapter])

  def withMdcEntries(entries: (String, Any)*): Directive0 =
    MdcLoggingDirectives.extractDiagnosticMarkerLog
      .map(log => log.mdc(log.mdc ++ entries.toMap))

  def withMdcEntry(key: String, value: Any): Directive0 =
    withMdcEntries((key, value))
}

object MdcLoggingDirectives extends MdcLoggingDirectives {
  // This is private because the only advantage of a DiagnosticLoggingAdapter is that you can mutate the MDC entries,
  // which is better done through the withMdcEntry and withMdcEntries directive.
  private val extractDiagnosticMarkerLog: Directive1[DiagnosticMarkerBusLoggingAdapter] =
    withMdcLogging.tflatMap { _ =>
      extractLog.flatMap { log =>
        // This asInstanceOf is tested and virtually guaranteed to work, but is there any way to do it without casting?
        val dmbla = log.asInstanceOf[DiagnosticMarkerBusLoggingAdapter]
        provide(dmbla)
      }
    }
}
