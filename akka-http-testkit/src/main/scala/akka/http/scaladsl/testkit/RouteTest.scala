/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Host, Upgrade, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.server._
import akka.http.scaladsl.settings.RoutingSettings
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture._
import akka.stream.SystemMaterializer
import akka.testkit.TestKit
import akka.util.ConstantFun
import com.typesafe.config.{ Config, ConfigFactory }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.DynamicVariable

trait RouteTest extends RequestBuilding with WSTestRequestBuilding with RouteTestResultComponent with MarshallingTestUtils {
  this: TestFrameworkInterface =>

  /** Override to supply a custom ActorSystem */
  protected def createActorSystem(): ActorSystem =
    ActorSystem(actorSystemNameFrom(getClass), testConfig)

  def actorSystemNameFrom(clazz: Class[_]) =
    clazz.getName
      .replace('.', '-')
      .replace('_', '-')
      .filter(_ != '$')

  def testConfigSource: String = ""
  def testConfig: Config = {
    val source = testConfigSource
    val config = if (source.isEmpty) ConfigFactory.empty() else ConfigFactory.parseString(source)
    config.withFallback(ConfigFactory.load())
  }
  implicit val system = createActorSystem()
  implicit def executor = system.dispatcher
  implicit val materializer = SystemMaterializer(system).materializer

  def cleanUp(): Unit = TestKit.shutdownActorSystem(system)

  private val dynRR = new DynamicVariable[RouteTestResult](null)
  private def result =
    if (dynRR.value ne null) dynRR.value
    else sys.error("This value is only available inside of a `check` construct!")

  def check[T](body: => T): RouteTestResult => T = result => dynRR.withValue(result.awaitResult)(body)

  private def responseSafe = if (dynRR.value ne null) dynRR.value.response else "<not available anymore>"

  def handled: Boolean = result.handled
  def response: HttpResponse = result.response
  def responseEntity: HttpEntity = result.entity
  def chunks: immutable.Seq[HttpEntity.ChunkStreamPart] = result.chunks
  def entityAs[T: FromEntityUnmarshaller: ClassTag](implicit timeout: Duration = 1.second): T = {
    def msg(e: Throwable) = s"Could not unmarshal entity to type '${implicitly[ClassTag[T]]}' for `entityAs` assertion: $e\n\nResponse was: $responseSafe"
    Await.result(Unmarshal(responseEntity).to[T].fast.recover[T] { case error => failTest(msg(error)) }, timeout)
  }
  def responseAs[T: FromResponseUnmarshaller: ClassTag](implicit timeout: Duration = 1.second): T = {
    def msg(e: Throwable) = s"Could not unmarshal response to type '${implicitly[ClassTag[T]]}' for `responseAs` assertion: $e\n\nResponse was: $responseSafe"
    Await.result(Unmarshal(response).to[T].fast.recover[T] { case error => failTest(msg(error)) }, timeout)
  }
  def contentType: ContentType = responseEntity.contentType
  def mediaType: MediaType = contentType.mediaType
  def charsetOption: Option[HttpCharset] = contentType.charsetOption
  def charset: HttpCharset = charsetOption getOrElse sys.error("Binary entity does not have charset")
  def headers: immutable.Seq[HttpHeader] = response.headers
  def header[T >: Null <: HttpHeader: ClassTag]: Option[T] = response.header[T](implicitly[ClassTag[T]])
  def header(name: String): Option[HttpHeader] = response.headers.find(_.is(name.toLowerCase))
  def status: StatusCode = response.status

  def closingExtension: String = chunks.lastOption match {
    case Some(HttpEntity.LastChunk(extension, _)) => extension
    case _                                        => ""
  }
  def trailer: immutable.Seq[HttpHeader] = chunks.lastOption match {
    case Some(HttpEntity.LastChunk(_, trailer)) => trailer
    case _                                      => Nil
  }

  def rejections: immutable.Seq[Rejection] = result.rejections
  def rejection: Rejection = {
    val r = rejections
    if (r.size == 1) r.head else failTest("Expected a single rejection but got %s (%s)".format(r.size, r))
  }

  def isWebSocketUpgrade: Boolean =
    status == StatusCodes.SwitchingProtocols && header[Upgrade].exists(_.hasWebSocket)

  /**
   * Asserts that the received response is a WebSocket upgrade response and the extracts
   * the chosen subprotocol and passes it to the handler.
   */
  def expectWebSocketUpgradeWithProtocol(body: String => Unit): Unit = {
    if (!isWebSocketUpgrade) failTest("Response was no WebSocket Upgrade response")
    header[`Sec-WebSocket-Protocol`] match {
      case Some(`Sec-WebSocket-Protocol`(Seq(protocol))) => body(protocol)
      case _ => failTest("No WebSocket protocol found in response.")
    }
  }

  /**
   * A dummy that can be used as `~> runRoute` to run the route but without blocking for the result.
   * The result of the pipeline is the result that can later be checked with `check`. See the
   * "separate running route from checking" example from ScalatestRouteTestSpec.scala.
   */
  def runRoute: RouteTestResult => RouteTestResult = ConstantFun.scalaIdentityFunction

  // there is already an implicit class WithTransformation in scope (inherited from akka.http.scaladsl.testkit.TransformerPipelineSupport)
  // however, this one takes precedence
  implicit class WithTransformation2(request: HttpRequest) {
    /**
     * Apply request to given routes for further inspection in `check { }` block.
     */
    def ~>[A, B](f: A => B)(implicit ta: TildeArrow[A, B]): ta.Out = ta(request, f)

    /**
     * Evaluate request against routes run in server mode for further
     * inspection in `check { }` block.
     *
     * Compared to [[~>]], the given routes are run in a fully fledged
     * server, which allows more types of directives to be tested at the
     * cost of additional overhead related with server setup.
     */
    def ~!>[A, B](f: A => B)(implicit tba: TildeBangArrow[A, B]): tba.Out = tba(request, f)
  }

  abstract class TildeArrow[A, B] {
    type Out
    def apply(request: HttpRequest, f: A => B): Out
  }

  case class DefaultHostInfo(host: Host, securedConnection: Boolean)
  object DefaultHostInfo {
    implicit def defaultHost: DefaultHostInfo = DefaultHostInfo(Host("example.com"), securedConnection = false)
  }
  object TildeArrow {
    implicit object InjectIntoRequestTransformer extends TildeArrow[HttpRequest, HttpRequest] {
      type Out = HttpRequest
      def apply(request: HttpRequest, f: HttpRequest => HttpRequest) = f(request)
    }
    implicit def injectIntoRoute(implicit timeout: RouteTestTimeout, defaultHostInfo: DefaultHostInfo): TildeArrow[RequestContext, Future[RouteResult]] { type Out = RouteTestResult } =
      new TildeArrow[RequestContext, Future[RouteResult]] {
        type Out = RouteTestResult
        def apply(request: HttpRequest, route: Route): Out = {
          implicit val executionContext: ExecutionContext = system.classicSystem.dispatcher
          val routingSettings = RoutingSettings(system)
          val routingLog = RoutingLog(system.classicSystem.log)

          val routeTestResult = new RouteTestResult(timeout.duration)
          val effectiveRequest =
            request.withEffectiveUri(
              securedConnection = defaultHostInfo.securedConnection,
              defaultHostHeader = defaultHostInfo.host)
          val ctx = new RequestContextImpl(effectiveRequest, routingLog.requestLog(effectiveRequest), routingSettings)

          val sealedExceptionHandler = ExceptionHandler.seal(testExceptionHandler)

          val semiSealedRoute = // sealed for exceptions but not for rejections
            Directives.handleExceptions(sealedExceptionHandler)(route)
          val deferrableRouteResult = semiSealedRoute(ctx)
          deferrableRouteResult.fast.foreach(routeTestResult.handleResult)(executionContext)
          routeTestResult
        }
      }
  }

  abstract class TildeBangArrow[A, B] {
    type Out
    def apply(request: HttpRequest, f: A => B): Out
  }

  object TildeBangArrow {
    implicit def injectIntoRoute(implicit timeout: RouteTestTimeout, serverSettings: ServerSettings): TildeBangArrow[RequestContext, Future[RouteResult]] { type Out = RouteTestResult } =
      new TildeBangArrow[RequestContext, Future[RouteResult]] {
        type Out = RouteTestResult
        def apply(request: HttpRequest, route: Route): Out = {
          val routeTestResult = new RouteTestResult(timeout.duration)
          val responseF = RouteTest.runRouteClientServer(request, route, serverSettings)
          val response = Await.result(responseF, timeout.duration)
          routeTestResult.handleResponse(response)
          routeTestResult
        }
      }
  }
}
private[http] object RouteTest {
  def runRouteClientServer(request: HttpRequest, route: Route, serverSettings: ServerSettings)(implicit system: ActorSystem): Future[HttpResponse] = {
    import system.dispatcher
    for {
      binding <- Http().bindAndHandle(route, "127.0.0.1", 0, settings = serverSettings)
      port = binding.localAddress.getPort
      targetUri = request.uri.withHost("127.0.0.1").withPort(port).withScheme("http")

      response <- Http().singleRequest(request.withUri(targetUri))
    } yield {
      binding.unbind()
      response
    }
  }
}
