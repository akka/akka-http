/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.{ Coder, Coders }
import akka.http.scaladsl.marshalling.{ Marshal, ToRequestMarshaller }
import akka.http.scaladsl.model.{ ContentTypes, HttpRequest, HttpResponse, StatusCode, StatusCodes, headers }
import akka.http.scaladsl.model.headers.{ HttpEncoding, HttpEncodings }
import akka.http.scaladsl.unmarshalling.{ FromResponseUnmarshaller, Unmarshal }
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ Materializer, SystemMaterializer }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object Client {
  type ProcessingStep = HttpRequest => Future[HttpResponse]
  type ClientDirective = ProcessingStep => ProcessingStep

  def apply(request: HttpRequest)(implicit system: ActorSystem): Future[HttpResponse] =
    defaultPipeline.apply(request)

  def defaultPipeline(implicit system: ActorSystem): ProcessingStep =
    defaultDirectives(system.dispatcher, SystemMaterializer(system).materializer).apply(runRequest)

  def runRequest(implicit system: ActorSystem): ProcessingStep = Http().singleRequest(_)

  def userClient[T: ToRequestMarshaller, U: FromResponseUnmarshaller](implicit system: ActorSystem): T => Future[U] = {
    import system.dispatcher
    t =>
      for {
        req <- Marshal(t).to[HttpRequest]
        response <- apply(req)
        u <- Unmarshal(response).to[U]
      } yield u
  }

  def defaultDirectives(implicit ec: ExecutionContext, mat: Materializer): ClientDirective = // FIXME: make val?
    codingSupport() andThen
      failNonOkResponses andThen
      redirectionSupport()

  def redirectionSupport(maxDepth: Int = 5 /* FIXME */ )(implicit ec: ExecutionContext): ClientDirective = { inner =>
    def next(req: HttpRequest /* , remainingRedirects */ ): Future[HttpResponse] = {
      def handleResponse(response: HttpResponse): Future[HttpResponse] = {
        import StatusCodes._
        response.status match {
          case code @ (Found | PermanentRedirect | TemporaryRedirect | PermanentRedirect) =>
            val location = response.header[headers.Location].getOrElse(throw new IllegalArgumentException(s"Response code [$code] indicated redirection but Location header was missing"))

            // FIXME: is it safe to keep all headers?
            // FIXME: Can we be sure that the request entity data is still valid?
            next(req.withUri(location.uri))
          case _ =>
            FastFuture.successful(response)
        }
      }

      val resF = inner(req)
      // FIXME: check spec which requests are allowed to be redirected
      if (req.method.isIdempotent) resF.flatMap(handleResponse)
      else resF
    }

    next
  }
  def codingSupport(supportedEncodings: Seq[Coder] = Coders.DefaultCoders)(implicit ec: ExecutionContext): ClientDirective = { inner => req =>
    val existing = req.header[headers.`Content-Encoding`].fold(Seq.empty[HttpEncoding])(_.encodings)
    val newHeader = headers.`Content-Encoding`(existing ++ supportedEncodings.map(_.encoding).diff(existing))
    val req1 = req.removeHeader("content-encoding").addHeader(newHeader) // FIXME: add a HttpMessage.replaceHeader method instead

    inner(req1).map { res =>
      val decoder = res.encoding match {
        case HttpEncodings.gzip =>
          Coders.Gzip
        case HttpEncodings.deflate =>
          Coders.Deflate
        case HttpEncodings.identity =>
          Coders.NoCoding
        case other =>
          // FIXME: log.warning(s"Unknown encoding [$other], not decoding")
          Coders.NoCoding
      } // FIXME: move to Coders

      decoder.decodeMessage(res)
    }
  }
  def failNonOkResponses(implicit ec: ExecutionContext, mat: Materializer): ClientDirective = { inner => req =>
    inner(req).flatMap { response =>
      response.status match {
        case StatusCodes.OK => Future.successful(response)
        case code =>
          if (response.entity.contentType == ContentTypes.`text/plain(UTF-8)`)
            response.entity.toStrict(1.second /* FIXME */ , 1000L /* FIXME */ ).transformWith { strict =>
              val text = strict.fold(ex => s"<Could not get response text (${ex.getMessage})>", _.data.utf8String)
              FastFuture.failed(new RuntimeException(s"Response code [$code] did not indicate a successful response. [$text]"))
            }
          else
            FastFuture.failed(new RuntimeException(s"Response code [$code] did not indicate a successful response."))
      }
    }
  }
}
