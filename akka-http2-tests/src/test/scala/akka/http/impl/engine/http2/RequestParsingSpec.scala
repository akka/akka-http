/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Accept, Cookie, Host }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.{ ByteString, OptionVal }
import org.scalatest.{ Inside, Inspectors }
import FrameEvent._
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException
import akka.http.impl.engine.http2.RequestParsing.ParseRequestResult
import akka.http.impl.engine.http2.hpack.HeaderDecompression
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.util.AkkaSpecWithMaterializer
import org.scalatest.exceptions.TestFailedException

import java.net.InetAddress
import java.net.InetSocketAddress

class RequestParsingSpec extends AkkaSpecWithMaterializer with Inside with Inspectors {
  "RequestParsing" should {

    /** Helper to test parsing */
    def parse(
      keyValuePairs:  Seq[(String, String)],
      data:           Source[ByteString, Any],
      attributes:     Attributes,
      uriParsingMode: Uri.ParsingMode,
      settings:       ServerSettings
    ): RequestParsing.ParseRequestResult = {
      val (serverSettings, parserSettings) = {
        val ps = settings.parserSettings.withUriParsingMode(uriParsingMode)
        (settings.withParserSettings(ps), ps)
      }
      val headerParser = HttpHeaderParser(parserSettings, log)

      val encoder = new HPackEncodingSupport {}
      val frame = HeadersFrame(1, data == Source.empty, endHeaders = true, encoder.encodeHeaderPairs(keyValuePairs), None)

      val parseRequest: Http2SubStream => ParseRequestResult = RequestParsing.parseRequest(headerParser, serverSettings, attributes)

      Source.single(frame)
        .via(new HeaderDecompression(headerParser, parserSettings))
        .map { // emulate demux
          case headers: ParsedHeadersFrame =>
            Http2SubStream(
              initialHeaders = headers,
              trailingHeaders = OptionVal.None,
              data = Right(data),
              correlationAttributes = Map.empty
            )
          case _ => throw new IllegalStateException("Unexpected frame") // compiler completeness check pleaser
        }
        .map(parseRequest)
        .runWith(Sink.head)
        .futureValue
    }

    def parseExpectOk(
      keyValuePairs:  Seq[(String, String)],
      data:           Source[ByteString, Any] = Source.empty,
      attributes:     Attributes              = Attributes(),
      uriParsingMode: Uri.ParsingMode         = Uri.ParsingMode.Relaxed,
      settings:       ServerSettings          = ServerSettings(system)): HttpRequest =
      parse(keyValuePairs, data, attributes, uriParsingMode, settings) match {
        case RequestParsing.OkRequest(req)      => req
        case RequestParsing.BadRequest(info, _) => fail(s"Failed parsing request: $info")
      }

    def parseExpectError(
      keyValuePairs:  Seq[(String, String)],
      data:           Source[ByteString, Any] = Source.empty,
      attributes:     Attributes              = Attributes(),
      uriParsingMode: Uri.ParsingMode         = Uri.ParsingMode.Relaxed,
      settings:       ServerSettings          = ServerSettings(system)): ErrorInfo =
      parse(keyValuePairs, data, attributes, uriParsingMode, settings) match {
        case RequestParsing.OkRequest(req)      => fail("Unexpectedly succeeded parsing request")
        case RequestParsing.BadRequest(info, _) => info
      }

    def parseExpectProtocolError(
      keyValuePairs:  Seq[(String, String)],
      data:           Source[ByteString, Any] = Source.empty,
      attributes:     Attributes              = Attributes(),
      uriParsingMode: Uri.ParsingMode         = Uri.ParsingMode.Relaxed,
      settings:       ServerSettings          = ServerSettings(system)): Http2ProtocolException =
      try {
        parse(keyValuePairs, data, attributes, uriParsingMode, settings)
        fail("expected parsing to throw")
      } catch {
        case futureValueEx: TestFailedException if futureValueEx.getCause.isInstanceOf[Http2ProtocolException] =>
          futureValueEx.getCause.asInstanceOf[Http2ProtocolException]
      }

    "follow RFC7540" should {

      // 8.1.2.1.  Pseudo-Header Fields

      // ... pseudo-header fields defined for responses MUST NOT
      // appear in requests.

      "not accept response pseudo-header fields in a request" in {
        val info = parseExpectError(
          keyValuePairs = Vector(
            ":scheme" -> "https",
            ":method" -> "GET",
            ":path" -> "/",
            ":status" -> "200"
          ))
        info.summary should ===("Malformed request: Pseudo-header ':status' is for responses only; it cannot appear in a request")
      }

      // All pseudo-header fields MUST appear in the header block before
      // regular header fields.  Any request or response that contains a
      // pseudo-header field that appears in a header block after a regular
      // header field MUST be treated as malformed...

      "not accept pseudo-header fields after regular headers" in {
        val pseudoHeaders = Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/"
        )
        forAll(pseudoHeaders.indices: Seq[Int]) { (insertPoint: Int) =>
          // Insert the Foo header so it occurs before at least one pseudo-header
          val (before, after) = pseudoHeaders.splitAt(insertPoint)
          val modified = before ++ Vector("Foo" -> "bar") ++ after
          parseExpectError(modified)
        }
      }

      // 8.1.2.2.  Connection-Specific Header Fields

      // ...any message containing connection-specific header fields MUST
      // be treated as malformed...

      "not accept connection-specific headers" in {
        // Add Connection header to indicate that Foo is a connection-specific header
        parseExpectError(Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "Connection" -> "foo",
          "Foo" -> "bar"
        ))
      }

      "not accept TE with other values than 'trailers'" in {

        // The only exception to this is the TE header field, which MAY be
        // present in an HTTP/2 request; when it is, it MUST NOT contain any
        // value other than "trailers".
        parseExpectError(Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "TE" -> "chunked",
        ))

      }

      "accept TE with 'trailers' as value" in {
        parseExpectOk(Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "TE" -> "trailers",
        ))
      }

      // 8.1.2.3.  Request Pseudo-Header Fields

      // The ":method" pseudo-header field includes the HTTP method
      // ...

      "parse the ':method' pseudo-header correctly" in {
        val methods = Seq("GET", "POST", "DELETE", "OPTIONS")
        forAll(methods) { (method: String) =>
          val request: HttpRequest = parseExpectOk(
            keyValuePairs = Vector(
              ":method" -> method,
              ":scheme" -> "https",
              ":path" -> "/"
            ))
          request.method.value should ===(method)
        }
      }

      // The ":scheme" pseudo-header field includes the scheme portion of
      // the target URI ([RFC3986], Section 3.1).
      //
      // ":scheme" is not restricted to "http" and "https" schemed URIs.  A
      // proxy or gateway can translate requests for non-HTTP schemes,
      // enabling the use of HTTP to interact with non-HTTP services.

      "parse the ':scheme' pseudo-header correctly" in {
        // ws/wss are not supported in HTTP/2, but they're useful for this test.
        // We're restricted in what we can test because the HttpRequest class
        // can't be constructed with any other schemes.
        val schemes = Seq("http", "https", "ws", "wss")
        forAll(schemes) { (scheme: String) =>
          val request: HttpRequest = parseExpectOk(
            keyValuePairs = Vector(
              ":method" -> "POST",
              ":scheme" -> scheme,
              ":path" -> "/"
            ))
          request.uri.scheme should ===(scheme)
        }
      }

      // The ":authority" pseudo-header field includes the authority
      // portion of the target URI ([RFC3986], Section 3.2).

      "follow RFC3986 for the ':path' pseudo-header" should {

        "parse a valid ':authority' (without userinfo)" in {
          // Examples from RFC3986
          val authorities = Seq(
            ("", "", None),
            ("ftp.is.co.za", "ftp.is.co.za", None),
            ("www.ietf.org", "www.ietf.org", None),
            ("[2001:db8::7]", "2001:db8::7", None),
            ("192.0.2.16:80", "192.0.2.16", Some(80)),
            ("example.com:8042", "example.com", Some(8042))
          )
          forAll(authorities) {
            case (authority, host, optPort) =>
              val request: HttpRequest = parseExpectOk(
                keyValuePairs = Vector(
                  ":method" -> "POST",
                  ":scheme" -> "https",
                  ":authority" -> authority,
                  ":path" -> "/"
                ))
              request.uri.authority.host.address should ===(host)
              request.uri.authority.port should ===(optPort.getOrElse(0))
          }
        }

        "reject an invalid ':authority'" in {

          val authorities = Seq("?", " ", "@", ":")
          forAll(authorities) { authority =>
            val info = parseExpectError(
              keyValuePairs = Vector(
                ":method" -> "POST",
                ":scheme" -> "https",
                ":authority" -> authority,
                ":path" -> "/"
              ))
            info.summary should include("http2-authority-pseudo-header")
          }
        }
      }

      // ... The authority
      // MUST NOT include the deprecated "userinfo" subcomponent for "http"
      // or "https" schemed URIs.

      // [Can't test any schemes that would allow userinfo, since restrictions
      // on HttpRequest objects mean we can only test http, https, ws and wss schemes,
      // none of which should probably allow userinfo in the authority.]
      "not accept a 'userinfo' value in the :authority pseudo-header for http and https" in {
        // Examples from RFC3986
        val authorities = Seq(
          "@localhost",
          "John.Doe@example.com",
          "cnn.example.com&story=breaking_news@10.0.0.1"
        )
        val schemes = Seq("http", "https")
        forAll(schemes) { (scheme: String) =>
          forAll(authorities) { (authority: String) =>
            val info = parseExpectError(
              keyValuePairs = Vector(
                ":method" -> "POST",
                ":scheme" -> scheme,
                ":authority" -> authority,
                ":path" -> "/"
              ))
            info.summary should startWith("Illegal http2-authority-pseudo-header")
          }
        }
      }

      // The ":path" pseudo-header field includes the path and query parts
      // of the target URI (the "path-absolute" production and optionally a
      // '?' character followed by the "query" production (see Sections 3.3
      // and 3.4 of [RFC3986]).

      "follow RFC3986 for the ':path' pseudo-header" should {

        def parsePath(path: String, uriParsingMode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri = {
          parseExpectOk(Seq(":method" -> "GET", ":scheme" -> "https", ":path" -> path), uriParsingMode = uriParsingMode).uri
        }

        def parsePathExpectError(path: String, uriParsingMode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): ErrorInfo =
          parseExpectError(Seq(":method" -> "GET", ":scheme" -> "https", ":path" -> path), uriParsingMode = uriParsingMode)

        // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
        //             / "*" / "+" / "," / ";" / "="

        // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"

        // path          = path-abempty    ; begins with "/" or is empty
        //               / path-absolute   ; begins with "/" but not "//"
        //               / path-noscheme   ; begins with a non-colon segment
        //               / path-rootless   ; begins with a segment
        //               / path-empty      ; zero characters
        // path-abempty  = *( "/" segment )
        // path-absolute = "/" [ segment-nz *( "/" segment ) ]
        // path-noscheme = segment-nz-nc *( "/" segment )
        // path-rootless = segment-nz *( "/" segment )
        // path-empty    = 0<pchar>
        // segment       = *pchar
        // segment-nz    = 1*pchar
        // segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
        // ; non-zero-length segment without any colon ":"
        // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"

        val pchar: Seq[Char] = {
          // RFC 3986, 2.3. Unreserved Characters
          // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
          val alphaDigit = for ((min, max) <- Seq(('a', 'z'), ('A', 'Z'), ('0', '9')); c <- min to max) yield c
          val unreserved = alphaDigit ++ Seq('-', '.', '_', '~')

          // RFC 3986, 2.2. Reserved Characters
          // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
          //             / "*" / "+" / "," / ";" / "="
          val subDelims = Seq('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')

          // RFC 3986, 3.3. Path
          // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
          unreserved ++ subDelims ++ Seq(':', '@')
        }

        val absolutePaths = Seq[(String, String)](
          "/" -> "/",
          "/foo" -> "/foo", "/foo/" -> "/foo/", "/foo//" -> "/foo//", "/foo///" -> "/foo///",
          "/foo/bar" -> "/foo/bar", "/foo//bar" -> "/foo//bar", "/foo//bar/" -> "/foo//bar/",
          "/a=b" -> "/a=b", "/%2f" -> "/%2F", "/x:0/y:1" -> "/x:0/y:1"
        ) ++ pchar.map {
            case '.' => "/." -> "/"
            case c   => ("/" + c) -> ("/" + c)
          }

        "parse a ':path' containing a 'path-absolute'" in {
          forAll(absolutePaths) {
            case (input, output) =>
              val uri = parsePath(input)
              uri.path.toString should ===(output)
              uri.rawQueryString should ===(None)
          }
        }

        "reject a ':path' that doesn't start with a 'path-absolute'" in {
          val invalidAbsolutePaths = Seq(
            "/ ", "x", "1", "%2f", "-", ".", "_", "~",
            "?", "&", "=", "#", ":", "?", "#", "[", "]", "@", " ",
            "http://localhost/foo"
          )
          forAll(invalidAbsolutePaths) { (absPath: String) =>
            val info = parsePathExpectError(absPath)
            info.summary should include("http2-path-pseudo-header")
          }
        }

        "reject a ':path' that doesn't start with a 'path-absolute' (not planning to fix)" in pendingUntilFixed {
          val invalidAbsolutePaths = Seq(
            // Illegal for path-absolute in RFC3986 to start with multiple slashes
            "//", "//x"
          )
          forAll(invalidAbsolutePaths) { (absPath: String) =>
            val info = parsePathExpectError(absPath, uriParsingMode = Uri.ParsingMode.Strict)
            info.summary should include("http2-path-pseudo-header")
          }
        }

        // query       = *( pchar / "/" / "?" )

        "parse a ':path' containing a 'query'" in {
          val queryChar: Seq[Char] = pchar ++ Seq('/', '?')

          val queries: Seq[(String, Option[Uri.Query])] = Seq(
            "" -> None,
            "name=ferret" -> Some(Uri.Query("name" -> "ferret")),
            "name=ferret&color=purple" -> Some(Uri.Query("name" -> "ferret", "color" -> "purple")),
            "field1=value1&field2=value2&field3=value3" -> Some(Uri.Query("field1" -> "value1", "field2" -> "value2", "field3" -> "value3")),
            "field1=value1&field1=value2&field2=value3" -> Some(Uri.Query("field1" -> "value1", "field1" -> "value2", "field2" -> "value3")),
            "first=this+is+a+field&second=was+it+clear+%28already%29%3F" -> Some(Uri.Query("first" -> "this is a field", "second" -> "was it clear (already)?")),
            "e0a72cb2a2c7" -> None
          ) ++ queryChar.map((c: Char) => (c.toString -> None))

          forAll(absolutePaths.take(3)) {
            case (inputPath, expectedOutputPath) =>
              forAll(queries) {
                case (rawQueryString, optParsedQuery) =>
                  val uri = parsePath(inputPath + "?" + rawQueryString)
                  uri.path.toString should ===(expectedOutputPath)
                  uri.rawQueryString should ===(Some(rawQueryString))

                  // How form-encoded query strings are parsed is not strictly part of the HTTP/2 and URI RFCs,
                  // but lets do a quick sanity check to ensure that form-encoded query strings are correctly
                  // parsed into values further up the parsing stack.
                  optParsedQuery.foreach { (expectedParsedQuery: Uri.Query) =>
                    uri.query() should contain theSameElementsAs (expectedParsedQuery)
                  }
              }
          }
        }

        "reject a ':path' containing an invalid 'query'" in pendingUntilFixed {
          val invalidQueries: Seq[String] = Seq(
            ":", "/", "?", "#", "[", "]", "@", " "
          )

          forAll(absolutePaths.take(3)) {
            case (inputPath, _) =>
              forAll(invalidQueries) { (query: String) =>
                parsePathExpectError(inputPath + "?" + query, uriParsingMode = Uri.ParsingMode.Strict)
              }
          }
        }

      }

      // ... A request in asterisk form includes the
      // value '*' for the ":path" pseudo-header field.

      "handle a ':path' with an asterisk" in pendingUntilFixed {
        val request: HttpRequest = parseExpectOk(
          keyValuePairs = Vector(
            ":method" -> "OPTIONS",
            ":scheme" -> "http",
            ":path" -> "*"
          ))
        request.uri.toString should ===("*") // FIXME: Compare in a better way
      }

      // [The ":path"] pseudo-header field MUST NOT be empty for "http" or "https"
      // URIs...

      "reject empty ':path' pseudo-headers for http and https" in {
        val schemes = Seq("http", "https")
        forAll(schemes) { (scheme: String) =>
          parseExpectError(
            keyValuePairs = Vector(
              ":method" -> "POST",
              ":scheme" -> scheme,
              ":path" -> ""
            ))
        }
      }

      // The exception to this rule is an
      // OPTIONS request for an "http" or "https" URI that does not include
      // a path component; these MUST include a ":path" pseudo-header field
      // with a value of '*' (see [RFC7230], Section 5.3.4).

      // [already tested above]

      // All HTTP/2 requests MUST include exactly one valid value for the
      // ":method", ":scheme", and ":path" pseudo-header fields, unless it is
      // a CONNECT request (Section 8.3).  An HTTP request that omits
      // mandatory pseudo-header fields is malformed

      // [assume CONNECT not supported]

      "reject requests without a mandatory pseudo-headers" in {
        val mandatoryPseudoHeaders = Seq(":method", ":scheme", ":path")
        forAll(mandatoryPseudoHeaders) { (name: String) =>
          val ex = parseExpectProtocolError(
            keyValuePairs = Vector(
              ":scheme" -> "https",
              ":method" -> "GET",
              ":path" -> "/"
            ).filter(_._1 != name))
          ex.getMessage should ===(s"Malformed request: Mandatory pseudo-header '$name' missing")
        }
      }

      "reject requests with more than one pseudo-header" in {
        val pseudoHeaders = Seq(":method" -> "POST", ":scheme" -> "http", ":path" -> "/other", ":authority" -> "example.org")
        forAll(pseudoHeaders) {
          case (name: String, alternative: String) =>
            val ex = parseExpectProtocolError(
              keyValuePairs = Vector(
                ":scheme" -> "https",
                ":method" -> "GET",
                ":authority" -> "akka.io",
                ":path" -> "/"
              ) :+ (name -> alternative))
            ex.getMessage should ===(s"Malformed request: Pseudo-header '$name' must not occur more than once")
        }
      }

      // 8.1.2.5.  Compressing the Cookie Header Field

      // If there are multiple Cookie header fields after
      // decompression, these MUST be concatenated into a single octet string
      // using the two-octet delimiter of 0x3B, 0x20 (the ASCII string "; ")
      // before being passed into ... a generic HTTP server application.

      "compress multiple 'cookie' headers into one modeled header" in {
        val cookieHeaders: Seq[(Seq[String], String)] = Vector(
          Seq("a=b") -> "a=b",
          Seq("a=b", "c=d") -> "a=b; c=d",
          Seq("a=b", "c=d", "e=f") -> "a=b; c=d; e=f",
          Seq("a=b; c=d", "e=f") -> "a=b; c=d; e=f",
          Seq("a=b", "c=d; e=f") -> "a=b; c=d; e=f"
        )
        forAll(cookieHeaders) {
          case (inValues, outValue) =>
            val httpRequest: HttpRequest = parseExpectOk(
              Vector(
                ":method" -> "GET",
                ":scheme" -> "https",
                ":authority" -> "localhost:8000",
                ":path" -> "/"
              ) ++ inValues.map("cookie" -> _)
            )
            val receivedCookieValues: Seq[String] = httpRequest.headers.collect {
              case c @ Cookie(_) => c.value
            }
            receivedCookieValues should contain theSameElementsAs Vector(outValue)
        }
      }

      // 8.1.3.  Examples

      "parse GET example" in {
        val request: HttpRequest = parseExpectOk(
          keyValuePairs = Vector(
            ":method" -> "GET",
            ":scheme" -> "https",
            ":path" -> "/resource",
            "host" -> "example.org",
            "accept" -> "image/jpeg"
          ))

        request.method should ===(HttpMethods.GET)
        request.uri.scheme should ===("https")
        request.uri.authority.host should ===(Uri.Host(""))
        request.uri.path should ===(Uri.Path./("resource"))
        request.uri.authority.port should ===(0)
        request.uri.authority.userinfo should ===("")
        request.attribute(Http2.streamId) should be(Some(1))
        request.headers should contain theSameElementsAs Vector(
          Host(Uri.Host("example.org")),
          Accept(MediaRange(MediaTypes.`image/jpeg`))
        )
        request.entity should ===(HttpEntity.Empty)
        request.protocol should ===(HttpProtocols.`HTTP/2.0`)
      }

      "parse POST example" in {
        val request: HttpRequest = parseExpectOk(
          keyValuePairs = Vector(
            ":method" -> "POST",
            ":scheme" -> "https",
            ":path" -> "/resource",
            "content-type" -> "image/jpeg",
            "host" -> "example.org",
            "content-length" -> "123"
          ),
          data = Source(Vector(ByteString(Array.fill(123)(0x00.toByte))))
        )

        request.method should ===(HttpMethods.POST)
        request.uri.scheme should ===("https")
        request.uri.authority.host should ===(Uri.Host(""))
        request.uri.path should ===(Uri.Path./("resource"))
        request.uri.authority.port should ===(0)
        request.uri.authority.userinfo should ===("")
        request.attribute(Http2.streamId) should be(Some(1))
        request.headers should contain theSameElementsAs Vector(
          Host(Uri.Host("example.org"))
        )
        inside(request.entity) {
          case entity: HttpEntity =>
            // FIXME: contentLength is not reported in all cases with HTTP/2
            // see https://github.com/akka/akka-http/issues/3843
            // entity.contentLength should ===(123.toLong)
            entity.contentType should ===(ContentType(MediaTypes.`image/jpeg`))
        }
        request.protocol should ===(HttpProtocols.`HTTP/2.0`)
      }

    }

    // Tests that don't come from an RFC document...

    "parse GET https://localhost:8000/ correctly" in {
      val request: HttpRequest = parseExpectOk(
        keyValuePairs = Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "localhost:8000",
          ":path" -> "/"
        ))

      request.method should ===(HttpMethods.GET)
      request.uri.scheme should ===("https")
      request.uri.authority.host should ===(Uri.Host("localhost"))
      request.uri.authority.port should ===(8000)
      request.uri.authority.userinfo should ===("")
      request.attribute(Http2.streamId) should be(Some(1))
      request.headers shouldBe empty
      request.entity should ===(HttpEntity.Empty)
      request.protocol should ===(HttpProtocols.`HTTP/2.0`)
    }

    "reject requests with multiple content length headers" in {
      val info = parseExpectError(
        keyValuePairs = Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "localhost:8000",
          ":path" -> "/",
          "content-length" -> "123",
          "content-length" -> "124",
        ))
      info.summary should ===(s"Malformed request: HTTP message must not contain more than one content-length header")
    }

    "reject requests with multiple content type headers" in {
      val info = parseExpectError(
        keyValuePairs = Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "localhost:8000",
          ":path" -> "/",
          "content-type" -> "text/json",
          "content-type" -> "text/json",
        ))
      info.summary should ===(s"Malformed request: HTTP message must not contain more than one content-type header")
    }

    "reject requests with too many headers" in {
      val maxHeaderCount = ServerSettings(system).parserSettings.maxHeaderCount
      val info = parseExpectError((0 to (maxHeaderCount + 1)).map(n => s"x-my-header-$n" -> n.toString).toVector)
      info.summary should ===(s"Malformed request: HTTP message contains more than the configured limit of $maxHeaderCount headers")
    }

    "add remote address request attribute if enabled" in {
      val theAddress = InetAddress.getByName("127.5.2.1")
      val request: HttpRequest = parseExpectOk(
        keyValuePairs = Vector(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "localhost:8000",
          ":path" -> "/"
        ), settings = ServerSettings(system).withRemoteAddressAttribute(true),
        attributes = HttpAttributes.remoteAddress(new InetSocketAddress(theAddress, 8080))
      )
      request.attributes(AttributeKeys.remoteAddress) should equal(RemoteAddress(theAddress, Some(8080)))
    }
  }
}
