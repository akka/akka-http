package akka.http.scaladsl.server.directives

import java.net.InetAddress

import scala.concurrent.Future
import scala.concurrent.duration.Duration

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.server.directives.ReverseProxyDirectives._

class ReverseProxyDirectivesSpec extends RoutingSpec {
  "The reverseProxy directive" should {
    "forward requests to the configured target" in {
      var receivedRequest: HttpRequest = null

      val targetConfig = ReverseProxyTargetConfig("https://target.domain:1234", false)
      val magnet = new ReverseProxyTargetMagnet {
        def config = targetConfig

        val httpClient: HttpRequest ⇒ Future[HttpResponse] = request ⇒ {
          receivedRequest = request
          Future.successful(HttpResponse())
        }
      }

      Get("http://notthetarget.domain/the/path?foo=bar&bar=baz") ~> reverseProxy(magnet) ~> check {
        status shouldBe StatusCodes.OK
        receivedRequest should not be null
        receivedRequest.effectiveUri(false) shouldBe Uri("https://target.domain:1234/the/path?foo=bar&bar=baz")
      }
    }

    "use unmatched the unmatched path as configured" in {
      var receivedRequest: HttpRequest = null

      val targetConfig = ReverseProxyTargetConfig("https://target.domain:1234", useUnmatchedPath = true)
      val magnet = new ReverseProxyTargetMagnet {
        def config = targetConfig

        val httpClient: HttpRequest ⇒ Future[HttpResponse] = request ⇒ {
          receivedRequest = request
          Future.successful(HttpResponse())
        }
      }

      Get("http://notthetarget.domain/the/path?foo=bar&bar=baz") ~> pathPrefix("the")(reverseProxy(magnet)) ~> check {
        status shouldBe StatusCodes.OK
        receivedRequest should not be null
        receivedRequest.effectiveUri(false) shouldBe Uri("https://target.domain:1234/path?foo=bar&bar=baz")
      }
    }

    "strip hop-by-hop headers" in {
      var receivedRequest: HttpRequest = null

      val targetConfig = ReverseProxyTargetConfig("https://target.domain:1234", false)
      val magnet = new ReverseProxyTargetMagnet {
        def config = targetConfig

        val httpClient: HttpRequest ⇒ Future[HttpResponse] = request ⇒ {
          receivedRequest = request
          Future.successful(HttpResponse())
        }
      }

      val request = Get("http://notthetarget.domain/the/path?foo=bar&bar=baz")
        .withHeaders(
          `X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost)),
          Connection("close"),
          `Proxy-Authenticate`(HttpChallenge("scheme", "realm")),
          `Proxy-Authorization`(BasicHttpCredentials("username", "password")),
          `Transfer-Encoding`(TransferEncodings.chunked),
          Upgrade(List(UpgradeProtocol("protocol")))
        )

      request ~> withRequestTimeout(Duration.Inf)(reverseProxy(magnet)) ~> check {
        status shouldBe StatusCodes.OK
        receivedRequest should not be null
        receivedRequest.header[`Timeout-Access`] should not be defined
        receivedRequest.header[Connection] should not be defined
        receivedRequest.header[`Proxy-Authenticate`] should not be defined
        receivedRequest.header[`Proxy-Authorization`] should not be defined
        receivedRequest.header[`Transfer-Encoding`] should not be defined
        receivedRequest.header[Upgrade] should not be defined
      }
    }
  }
}
