/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import akka.actor.{ ActorSystem, ClassicActorSystemProvider }
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
import scala.util.Try

object Client {
  type ClientRoute = HttpRequest => Future[HttpResponse]
  type ClientDirective = ClientRoute => ClientRoute

  def apply(request: HttpRequest)(implicit system: ClassicActorSystemProvider): Future[HttpResponse] =
    defaultPipeline.apply(request)

  def defaultPipeline(implicit system: ClassicActorSystemProvider): ClientRoute =
    defaultDirectives(system.classicSystem.dispatcher, SystemMaterializer(system).materializer, system).apply(runRequest)

  def runRequest(implicit system: ClassicActorSystemProvider): ClientRoute = Http().singleRequest(_)

  def userClient[T: ToRequestMarshaller, U: FromResponseUnmarshaller](implicit system: ClassicActorSystemProvider): T => Future[U] = {
    implicit val ec = system.classicSystem.dispatcher
    t =>
      for {
        req <- Marshal(t).to[HttpRequest]
        response <- apply(req)
        u <- Unmarshal(response).to[U]
      } yield u
  }

  def defaultDirectives(implicit ec: ExecutionContext, mat: Materializer, system: ClassicActorSystemProvider): ClientDirective = // FIXME: make val?
    codingSupport() andThen
      failNonOkResponses andThen
      redirectionSupport() andThen
      retryRequests()

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

  def retryRequests(retryDecider: RetryContext => RetryResult = defaultRetry())(implicit ec: ExecutionContext, system: ClassicActorSystemProvider): ClientDirective = { inner => req =>
    def runOne(attempts: Int): Future[HttpResponse] =
      inner(req).transformWith { result =>
        retryDecider(RetryContext(req, result, attempts + 1)) match {
          case RetryResult.PropagateLastResult => Future.fromTry(result)
          case RetryResult.RetryNow            => runOne(attempts + 1)
          case RetryResult.RetryLater(after) =>
            akka.pattern.after(after, system.classicSystem.scheduler) {
              runOne(attempts + 1)
            }
        }
      }

    runOne(0)
  }

  sealed trait RetryContext {
    def request: HttpRequest
    def responseResult: Try[HttpResponse]
    def attempts: Int
  }
  object RetryContext {
    private[http] def apply(request: HttpRequest, result: Try[HttpResponse], attempts: Int): RetryContext =
      Impl(request, result, attempts)

    private[http] case class Impl(
      request:        HttpRequest,
      responseResult: Try[HttpResponse],
      attempts:       Int
    ) extends RetryContext
  }
  sealed trait RetryResult
  object RetryResult {
    case object PropagateLastResult extends RetryResult
    case object RetryNow extends RetryResult
    final case class RetryLater(after: FiniteDuration) extends RetryResult
  }

  def defaultRetry(maxRetries: Int = 5 /* FIXME */ )(retryContext: RetryContext): RetryResult =
    RetryResult.PropagateLastResult // FIXME
}
