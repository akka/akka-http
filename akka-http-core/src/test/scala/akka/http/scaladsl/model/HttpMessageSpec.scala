/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.net.InetAddress

import akka.util.ByteString
import headers._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import akka.http.javadsl.{ model => jm }
import akka.stream.scaladsl.TLSPlacebo

class HttpMessageSpec extends AnyWordSpec with Matchers {

  def test(uri: String, effectiveUri: String, headers: HttpHeader*) =
    HttpRequest.effectiveUri(Uri(uri), List(headers: _*), securedConnection = false, null) shouldEqual Uri(effectiveUri)

  def fail(uri: String, hostHeader: Host) =
    an[IllegalUriException] should be thrownBy
      HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null)

  def failWithNoHostHeader(hostHeader: Option[Host], details: String) = {
    val thrown = the[IllegalUriException] thrownBy
      HttpRequest.effectiveUri(Uri("/relative"), hostHeader.toList, securedConnection = false, Host(""))

    thrown should have message
      s"Cannot establish effective URI of request to `/relative`, request has a relative URI and $details: " +
      "consider setting `akka.http.server.default-host-header`"
  }

  "HttpRequest" should {
    "provide an effective URI for relative URIs or matching Host-headers" in {
      test("/segment", "http://example.com/segment", Host("example.com"))
      test("http://example.com/", "http://example.com/", Host("example.com"))
      test("http://example.com:8080/", "http://example.com:8080/", Host("example.com", 8080))
      test("/websocket", "ws://example.com/websocket", Host("example.com"), Upgrade(List(UpgradeProtocol("websocket"))))
    }

    "throw IllegalUriException for non-matching Host-headers" in {
      fail("http://example.net/", Host("example.com"))
      fail("http://example.com:8080/", Host("example.com"))
      fail("http://example.com/", Host("example.com", 8080))
    }

    "throw IllegalUriException for relative URI with no default Host header" in {
      failWithNoHostHeader(None, "is missing a `Host` header")
    }

    "throw IllegalUriException for relative URI with empty Host header and no default Host header" in {
      failWithNoHostHeader(Some(Host("")), "an empty `Host` header")
    }

    "throw IllegalUriException for an invalid URI schema" in {
      an[IllegalUriException] should be thrownBy
        HttpRequest(uri = Uri("htp://example.com"))
    }

    "throw IllegalUriException for empty URI" in {
      an[IllegalUriException] should be thrownBy
        HttpRequest(uri = Uri())
    }

    "take attributes into account for hashCode calculation" in {
      val orig = HttpRequest()
      val changed = orig.addAttribute(AttributeKeys.remoteAddress, RemoteAddress(InetAddress.getLocalHost))

      orig should not equal (changed)
      orig.hashCode() should not equal (changed.hashCode())
    }
  }
  "HttpResponse" should {
    "take attributes into account for hashCode calculation" in {
      val orig = HttpResponse()
      val changed = orig.addAttribute(AttributeKeys.remoteAddress, RemoteAddress(InetAddress.getLocalHost))

      orig should not equal (changed)
      orig.hashCode() should not equal (changed.hashCode())
    }
  }

  "HttpMessage" should {
    "not throw a ClassCastException on header[`Content-Type`]" in {
      val entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString.fromString("hello akka"))
      HttpResponse(entity = entity).header[`Content-Type`] shouldBe Some(`Content-Type`(ContentTypes.`text/plain(UTF-8)`))
    }
    "retrieve all headers of a given class when calling headers[...]" in {
      val oneCookieHeader = `Set-Cookie`(HttpCookie("foo", "bar"))
      val anotherCookieHeader = `Set-Cookie`(HttpCookie("foz", "baz"))
      val hostHeader = Host("akka.io")
      val request = HttpRequest().withHeaders(oneCookieHeader, anotherCookieHeader, hostHeader)
      request.headers[`Set-Cookie`] should ===(Seq(oneCookieHeader, anotherCookieHeader))
    }
    "retrieve an attribute by key" in {
      val oneStringKey = AttributeKey[String]("one")
      // keys with the same type but different names should be different
      val otherStringKey = AttributeKey[String]("other")
      // it should be possible to use 'Java attribute keys' in the Scala API's
      val intKey = jm.AttributeKey.create("int", classOf[Int])
      // keys with the same name but different types should be different
      val otherIntKey = AttributeKey[Int]("other")

      val oneString = "A string attribute!"
      val otherString = "Another"
      val int = 42
      val otherInt = 37

      val request = HttpRequest()
        .addAttribute(oneStringKey, oneString)
        .addAttribute(otherStringKey, otherString)
        .addAttribute(intKey, int)
        .addAttribute(otherIntKey, otherInt)

      request.attribute(oneStringKey) should be(Some(oneString))
      request.attribute(otherStringKey) should be(Some(otherString))
      request.attribute(intKey) should be(Some(int))
      request.attribute(otherIntKey) should be(Some(otherInt))

      val smaller = request.removeAttribute(intKey)
      smaller.attribute(otherStringKey) should be(Some(otherString))
      smaller.attribute(intKey) should be(None)
    }
    "support ssl attribute" in {
      val request = HttpRequest()
        .addAttribute(AttributeKeys.sslSession, SslSessionInfo(TLSPlacebo.dummySession))

      request.attribute(AttributeKeys.sslSession) should be(Some(SslSessionInfo(TLSPlacebo.dummySession)))

      val javaRequest: jm.HttpRequest = request
      javaRequest.getAttribute(jm.AttributeKeys.sslSession).get() should be(SslSessionInfo(TLSPlacebo.dummySession))
    }
  }

}
