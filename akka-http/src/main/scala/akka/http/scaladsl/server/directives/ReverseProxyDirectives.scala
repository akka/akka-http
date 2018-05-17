package akka.http.scaladsl.server.directives

import scala.concurrent.Future
import scala.util.Success

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.ReverseProxyDirectives.ReverseProxyTargetMagnet
import akka.http.scaladsl.server.{ RequestContext, Route }
import akka.util.ByteString

trait ReverseProxyDirectives {

  def reverseProxy(target: ReverseProxyTargetMagnet): Route =
    extractExecutionContext { implicit ec ⇒
      extractMaterializer { implicit mat ⇒
        // todo customize response
        def processTimeout(proxyResult: Future[HttpResponse]): HttpRequest ⇒ HttpResponse = _ ⇒ {
          proxyResult.andThen {
            case Success(proxyResponse) ⇒ proxyResponse.discardEntityBytes()
          }

          HttpResponse(StatusCodes.ServiceUnavailable, entity =
            HttpEntity.Strict(
              ContentTypes.`application/json`,
              ByteString("""{"error":"The server was not able to produce a timely response to your request."}""")
            )
          )
        }

        extractRequestContext { ctx ⇒
          // we don't need to use request.effectiveUri here since we're going to overwrite the scheme and authority
          val incomingUri = ctx.request.uri
          val outgoingUri =
            (if (target.config.useUnmatchedPath) incomingUri.withPath(ctx.unmatchedPath) else incomingUri)
              .withAuthority(target.config.targetAuthority)
              .withScheme(target.config.targetScheme)

          val outgoingRequest = ctx.request.withUri(outgoingUri)
          val eventualResponse = target.httpClient(outgoingRequest)

          withRequestTimeoutResponse(processTimeout(eventualResponse)) {
            mapRequestContext(mapProxyContext) {
              complete(eventualResponse)
            }
          }
        }
      }
    }

  // remove any headers that shouldn't be passed through a proxy
  private def mapProxyContext(ctx: RequestContext): RequestContext = {
    val incomingHeaders = ctx.request.headers

    val remoteAddressOption = ctx.request.header[`Remote-Address`].map(_.address)
    val xForwardedForAddressesOption = ctx.request.header[`X-Forwarded-For`].map(_.addresses)
    //TODO: Add `Via` HttpHeader as defined in https://tools.ietf.org/html/rfc2616#section-14.45 in akka.http.scaladsl.model.incomingHeaders

    val updatedXRealIpHeaderOption = ctx.request.header[`X-Real-Ip`]
      .map(_.address)
      .orElse(xForwardedForAddressesOption.flatMap(_.headOption))
      .orElse(remoteAddressOption)
      .map(`X-Real-Ip`(_))

    val updatedXForwardedForHeaderOption = remoteAddressOption
      .map(_ +: xForwardedForAddressesOption.getOrElse(Nil))
      .map(`X-Forwarded-For`(_))

    val outgoingHeaders = incomingHeaders.flatMap {
      case _: `X-Real-Ip`           ⇒ updatedXRealIpHeaderOption
      case _: `X-Forwarded-For`     ⇒ updatedXForwardedForHeaderOption
      case _: `Timeout-Access`      ⇒ Nil
      case _: Connection            ⇒ Nil
      // keep alive header is not included in modeled headers
      case _: `Proxy-Authenticate`  ⇒ Nil
      case _: `Proxy-Authorization` ⇒ Nil
      case _: `Transfer-Encoding`   ⇒ Nil
      case h if h.is("te")          ⇒ Nil
      case h if h.is("trailers")    ⇒ Nil
      case _: Upgrade               ⇒ Nil
      case h                        ⇒ Some(h)
    }

    ctx.mapRequest(_.withHeaders(outgoingHeaders))
  }
}

object ReverseProxyDirectives extends ReverseProxyDirectives {

  trait ReverseProxyTargetConfig {
    def targetScheme: String
    def targetAuthority: Authority
    def useUnmatchedPath: Boolean
  }

  object ReverseProxyTargetConfig {
    def apply(baseUri: Uri, useUnmatchedPath: Boolean): ReverseProxyTargetConfig = ReverseProxyTargetConfigImpl(baseUri, useUnmatchedPath)
  }

  private case class ReverseProxyTargetConfigImpl(baseUri: Uri, useUnmatchedPath: Boolean) extends ReverseProxyTargetConfig {
    val targetScheme = baseUri.scheme
    val targetAuthority = baseUri.authority
  }

  trait ReverseProxyTargetMagnet {
    def config: ReverseProxyTargetConfig
    def httpClient: HttpRequest ⇒ Future[HttpResponse]
  }

  object ReverseProxyTargetMagnet {
    import scala.language.implicitConversions

    implicit def fromConfig(targetConfig: ReverseProxyTargetConfig)(implicit system: ActorSystem) =
      new ReverseProxyTargetMagnet {
        val config = targetConfig
        val httpClient = Http().singleRequest(_)
      }

    implicit def fromUri(uri: Uri)(implicit system: ActorSystem) = fromConfig(ReverseProxyTargetConfigImpl(uri, false))
  }
}
