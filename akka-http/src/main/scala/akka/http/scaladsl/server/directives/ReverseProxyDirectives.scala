/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import scala.concurrent.Future
import scala.util.Success
import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.ReverseProxyDirectives.{ ReverseProxyTargetConfig, ReverseProxyTargetMagnet }
import akka.util.ByteString

trait ReverseProxyDirectives {

  @ApiMayChange
  def reverseProxy(target: ReverseProxyTargetMagnet): Route =
    extractExecutionContext { implicit ec =>
      extractMaterializer { implicit mat =>
        // todo customize response
        // drain response entity if incoming request timeout occurs
        def processTimeout(proxyResult: Future[HttpResponse]): HttpRequest => HttpResponse = _ => {
          proxyResult.andThen {
            case Success(proxyResponse) => proxyResponse.discardEntityBytes()
          }

          HttpResponse(StatusCodes.ServiceUnavailable, entity =
            HttpEntity.Strict(
              ContentTypes.`application/json`,
              ByteString("""{"error":"The server was not able to produce a timely response to your request."}""")
            )
          )
        }

        extractRequestContext { ctx =>
          // we don't need to use request.effectiveUri here since we're going to overwrite the scheme and authority
          val incomingUri = ctx.request.uri
          val outgoingUri =
            (if (target.config.useUnmatchedPath) incomingUri.withPath(ctx.unmatchedPath) else incomingUri)
              .withAuthority(target.config.targetAuthority)
              .withScheme(target.config.targetScheme)

          val outgoingRequest = mapProxyRequest(ctx.request, target.config).withUri(outgoingUri)
          val eventualResponse = target.httpClient(outgoingRequest)

          withRequestTimeoutResponse(processTimeout(eventualResponse)) {
            complete(eventualResponse)
          }
        }
      }
    }

  // remove any headers that shouldn't be passed through a proxy
  private def mapProxyRequest(request: HttpRequest, config: ReverseProxyTargetConfig): HttpRequest = {
    val incomingHeaders = request.headers

    val remoteAddressOption = request.header[`Remote-Address`].map(_.address)
    val xForwardedForAddressesOption = request.header[`X-Forwarded-For`].map(_.addresses)

    val updatedXRealIpHeaderOption = request.header[`X-Real-Ip`]
      .map(_.address)
      .orElse(xForwardedForAddressesOption.flatMap(_.headOption))
      .orElse(remoteAddressOption)
      .map(`X-Real-Ip`(_))

    val updatedXForwardedForHeaderOption = remoteAddressOption
      .map(_ +: xForwardedForAddressesOption.getOrElse(Nil))
      .map(`X-Forwarded-For`(_))

    val intermediary = {
      val (protocol, version) = {
        val parts = request.protocol.value.split('/')
        if (parts(0) == "HTTP" && parts.length > 1) None -> parts(1)
        else if (parts.length > 1) Some(parts(0)) -> parts(1)
        else None -> parts(0)
      }

      config.viaId.map(ViaIntermediary(protocol, version, _))
    }

    val maybeVia = request.header[Via]
      .map(via => via.copy(intermediaries = via.intermediaries ++ intermediary))
      .orElse(intermediary.map(i => Via(List(i))))

    val outgoingHeaders = incomingHeaders.flatMap {
      // region hop-by-hop headers https://tools.ietf.org/html/rfc2616#section-13.5.1
      case _: Connection            => Nil
      // keep alive header is not included in modeled headers
      case _: `Proxy-Authenticate`  => Nil
      case _: `Proxy-Authorization` => Nil
      case h if h.is("te")          => Nil
      case h if h.is("trailers")    => Nil
      case _: `Transfer-Encoding`   => Nil
      case _: Upgrade               => Nil
      // endregion
      case _: `X-Real-Ip`           => updatedXRealIpHeaderOption
      case _: `X-Forwarded-For`     => updatedXForwardedForHeaderOption
      case _: `Timeout-Access`      => Nil
      case _: Via                   => Nil // added back at the end
      case h                        => Some(h)
    } ++ maybeVia

    request.withHeaders(outgoingHeaders)
  }
}

object ReverseProxyDirectives extends ReverseProxyDirectives {

  trait ReverseProxyTargetConfig {
    def targetScheme: String
    def targetAuthority: Authority
    def useUnmatchedPath: Boolean
    def viaId: Option[String]
  }

  object ReverseProxyTargetConfig {
    def apply(baseUri: Uri, useUnmatchedPath: Boolean): ReverseProxyTargetConfig = ReverseProxyTargetConfigImpl(baseUri, useUnmatchedPath)
  }

  private case class ReverseProxyTargetConfigImpl(
    baseUri:          Uri,
    useUnmatchedPath: Boolean,
    viaId:            Option[String] = None
  ) extends ReverseProxyTargetConfig {
    val targetScheme = baseUri.scheme
    val targetAuthority = baseUri.authority
  }

  trait ReverseProxyTargetMagnet {
    def config: ReverseProxyTargetConfig
    def httpClient: HttpRequest => Future[HttpResponse]
  }

  object ReverseProxyTargetMagnet {
    import scala.language.implicitConversions

    implicit def fromConfig(targetConfig: ReverseProxyTargetConfig)(implicit system: ActorSystem) =
      new ReverseProxyTargetMagnet {
        val config = targetConfig
        val httpClient: HttpRequest => Future[HttpResponse] = Http().singleRequest(_)
      }

    implicit def fromUri(uri: Uri)(implicit system: ActorSystem) = fromConfig(ReverseProxyTargetConfigImpl(uri, false))
  }
}
