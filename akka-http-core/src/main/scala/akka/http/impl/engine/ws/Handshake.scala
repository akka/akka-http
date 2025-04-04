/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import java.util.Random

import akka.annotation.InternalApi

import scala.collection.immutable
import scala.collection.immutable.Seq
import akka.event.LoggingAdapter
import akka.http.impl.util._
import akka.http.impl.engine.server.UpgradeToOtherProtocolResponseHeader
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.WebSocketSettings
import akka.stream.{ FlowShape, Graph }
import akka.util.OptionVal

/**
 * Server-side implementation of the WebSocket handshake
 *
 * INTERNAL API
 */
@InternalApi
private[http] object Handshake {
  val CurrentWebSocketVersion = 13

  object Server {
    /**
     *  Validates a client WebSocket handshake. Returns either `OptionVal.Some(UpgradeToWebSocketLowLevel)` or
     *  `OptionVal.None`
     *
     *  From: http://tools.ietf.org/html/rfc6455#section-4.2.1
     *
     *  1.   An HTTP/1.1 or higher GET request, including a "Request-URI"
     *        [RFC2616] that should be interpreted as a /resource name/
     *        defined in Section 3 (or an absolute HTTP/HTTPS URI containing
     *        the /resource name/).
     *
     *   2.   A |Host| header field containing the server's authority.
     *
     *   3.   An |Upgrade| header field containing the value "websocket",
     *        treated as an ASCII case-insensitive value.
     *
     *   4.   A |Connection| header field that includes the token "Upgrade",
     *        treated as an ASCII case-insensitive value.
     *
     *   5.   A |Sec-WebSocket-Key| header field with a base64-encoded (see
     *        Section 4 of [RFC4648]) value that, when decoded, is 16 bytes in
     *        length.
     *
     *   6.   A |Sec-WebSocket-Version| header field, with a value of 13.
     *
     *   7.   Optionally, an |Origin| header field.  This header field is sent
     *        by all browser clients.  A connection attempt lacking this
     *        header field SHOULD NOT be interpreted as coming from a browser
     *        client.
     *
     *   8.   Optionally, a |Sec-WebSocket-Protocol| header field, with a list
     *        of values indicating which protocols the client would like to
     *        speak, ordered by preference.
     *
     *   9.   Optionally, a |Sec-WebSocket-Extensions| header field, with a
     *        list of values indicating which extensions the client would like
     *        to speak.  The interpretation of this header field is discussed
     *        in Section 9.1.
     *
     *  10.   Optionally, other header fields, such as those used to send
     *        cookies or request authentication to a server.  Unknown header
     *        fields are ignored, as per [RFC2616].
     */
    def websocketUpgrade(headers: List[HttpHeader], hostHeaderPresent: Boolean, settings: WebSocketSettings, log: LoggingAdapter): OptionVal[UpgradeToWebSocketLowLevel] = {

      // notes on Headers that re REQUIRE to be present here:
      // - Host header is validated in general HTTP logic
      // - Origin header is optional and, if required, should be validated
      //   on higher levels (routing, application logic)
      //
      // TODO See #18709 Extension support is optional in WS and currently unsupported.
      //
      // these are not needed directly, we verify their presence and correctness only:
      // - Upgrade
      // - Connection
      // - `Sec-WebSocket-Version`
      def hasAllRequiredWebsocketUpgradeHeaders: Boolean = {
        // single-pass through the headers list while collecting all needed requirements
        // this way we avoid scanning the requirements list 3 times (as we would with collect/find)
        val it = headers.iterator
        var requirementsMet = 0
        val targetRequirements = 3
        while (it.hasNext && (requirementsMet != targetRequirements)) it.next() match {
          case u: Upgrade                 => if (u.hasWebSocket) requirementsMet += 1
          case c: Connection              => if (c.hasUpgrade) requirementsMet += 1
          case v: `Sec-WebSocket-Version` => if (v.hasVersion(CurrentWebSocketVersion)) requirementsMet += 1
          case _                          => // continue...
        }
        requirementsMet == targetRequirements
      }

      if (hasAllRequiredWebsocketUpgradeHeaders) {
        val key = HttpHeader.fastFind(classOf[`Sec-WebSocket-Key`], headers)
        if (key.isDefined && key.get.isValid) {
          val protocol = HttpHeader.fastFind(classOf[`Sec-WebSocket-Protocol`], headers)

          val clientSupportedSubprotocols = protocol match {
            case OptionVal.Some(p) => p.protocols
            case _                 => Nil
          }

          val header = new UpgradeToWebSocketLowLevel {
            def requestedProtocols: Seq[String] = clientSupportedSubprotocols

            def handle(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]], subprotocol: Option[String]): HttpResponse = {
              require(
                subprotocol.forall(chosen => clientSupportedSubprotocols.contains(chosen)),
                s"Tried to choose invalid subprotocol '$subprotocol' which wasn't offered by the client: [${requestedProtocols.mkString(", ")}]")
              buildResponse(key.get, handler, subprotocol, settings, log)
            }

            def handleFrames(handlerFlow: Graph[FlowShape[FrameEvent, FrameEvent], Any], subprotocol: Option[String]): HttpResponse =
              handle(Left(handlerFlow), subprotocol)

            override def handleMessages(handlerFlow: Graph[FlowShape[Message, Message], Any], subprotocol: Option[String] = None): HttpResponse =
              handle(Right(handlerFlow), subprotocol)
          }
          OptionVal.Some(header)
        } else OptionVal.None
      } else OptionVal.None
    }

    /*
      From: http://tools.ietf.org/html/rfc6455#section-4.2.2

      1.  A Status-Line with a 101 response code as per RFC 2616
          [RFC2616].  Such a response could look like "HTTP/1.1 101
          Switching Protocols".

      2.  An |Upgrade| header field with value "websocket" as per RFC
          2616 [RFC2616].

      3.  A |Connection| header field with value "Upgrade".

      4.  A |Sec-WebSocket-Accept| header field.  The value of this
          header field is constructed by concatenating /key/, defined
          above in step 4 in Section 4.2.2, with the string "258EAFA5-
          E914-47DA-95CA-C5AB0DC85B11", taking the SHA-1 hash of this
          concatenated value to obtain a 20-byte value and base64-
          encoding (see Section 4 of [RFC4648]) this 20-byte hash.
    */
    def buildResponse(key: `Sec-WebSocket-Key`, handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]], subprotocol: Option[String], settings: WebSocketSettings, log: LoggingAdapter): HttpResponse = {
      val frameHandler = handler match {
        case Left(frameHandler) => frameHandler
        case Right(messageHandler) =>
          WebSocket.stack(serverSide = true, settings, log = log).join(messageHandler)
      }

      HttpResponse(
        StatusCodes.SwitchingProtocols,
        subprotocol.map(p => `Sec-WebSocket-Protocol`(Seq(p))).toList :::
          List(
            UpgradeHeader,
            ConnectionUpgradeHeader,
            `Sec-WebSocket-Accept`.forKey(key),
            UpgradeToOtherProtocolResponseHeader(WebSocket.framing.join(frameHandler))))
    }
  }

  object Client {
    case class NegotiatedWebSocketSettings(subprotocol: Option[String])

    /**
     * Builds a WebSocket handshake request.
     */
    def buildRequest(uri: Uri, extraHeaders: immutable.Seq[HttpHeader], subprotocols: Seq[String], random: Random): (HttpRequest, `Sec-WebSocket-Key`) = {
      val keyBytes = new Array[Byte](16)
      random.nextBytes(keyBytes)
      val key = `Sec-WebSocket-Key`(keyBytes)
      val protocol =
        if (subprotocols.nonEmpty) `Sec-WebSocket-Protocol`(subprotocols) :: Nil
        else Nil
      //version, protocol, extensions, origin

      val headers = Seq(
        UpgradeHeader,
        ConnectionUpgradeHeader,
        key,
        SecWebSocketVersionHeader) ++ protocol ++ extraHeaders

      (HttpRequest(HttpMethods.GET, uri.toRelative, headers), key)
    }

    /**
     * Tries to validate the HTTP response. Returns either Right(settings) or an error message if
     * the response cannot be validated.
     */
    def validateResponse(response: HttpResponse, subprotocols: Seq[String], key: `Sec-WebSocket-Key`): Either[String, NegotiatedWebSocketSettings] = {
      /*
       From http://tools.ietf.org/html/rfc6455#section-4.1

       1.  If the status code received from the server is not 101, the
           client handles the response per HTTP [RFC2616] procedures.  In
           particular, the client might perform authentication if it
           receives a 401 status code; the server might redirect the client
           using a 3xx status code (but clients are not required to follow
           them), etc.  Otherwise, proceed as follows.

       2.  If the response lacks an |Upgrade| header field or the |Upgrade|
           header field contains a value that is not an ASCII case-
           insensitive match for the value "websocket", the client MUST
           _Fail the WebSocket Connection_.

       3.  If the response lacks a |Connection| header field or the
           |Connection| header field doesn't contain a token that is an
           ASCII case-insensitive match for the value "Upgrade", the client
           MUST _Fail the WebSocket Connection_.

       4.  If the response lacks a |Sec-WebSocket-Accept| header field or
           the |Sec-WebSocket-Accept| contains a value other than the
           base64-encoded SHA-1 of the concatenation of the |Sec-WebSocket-
           Key| (as a string, not base64-decoded) with the string "258EAFA5-
           E914-47DA-95CA-C5AB0DC85B11" but ignoring any leading and
           trailing whitespace, the client MUST _Fail the WebSocket
           Connection_.

       5.  If the response includes a |Sec-WebSocket-Extensions| header
           field and this header field indicates the use of an extension
           that was not present in the client's handshake (the server has
           indicated an extension not requested by the client), the client
           MUST _Fail the WebSocket Connection_.  (The parsing of this
           header field to determine which extensions are requested is
           discussed in Section 9.1.)

       6.  If the response includes a |Sec-WebSocket-Protocol| header field
           and this header field indicates the use of a subprotocol that was
           not present in the client's handshake (the server has indicated a
           subprotocol not requested by the client), the client MUST _Fail
           the WebSocket Connection_.
     */

      trait Expectation extends (HttpResponse => Option[String]) { outer =>
        def &&(other: HttpResponse => Option[String]): Expectation =
          new Expectation {
            def apply(v1: HttpResponse): Option[String] =
              outer(v1).orElse(other(v1))
          }
      }

      def check[T](value: HttpResponse => T)(condition: T => Boolean, msg: T => String): Expectation =
        new Expectation {
          def apply(resp: HttpResponse): Option[String] = {
            val v = value(resp)
            if (condition(v)) None
            else Some(msg(v))
          }
        }

      def compare(candidate: HttpHeader, caseInsensitive: Boolean): Option[HttpHeader] => Boolean = {
        case Some(`candidate`) if !caseInsensitive => true
        case Some(header) if caseInsensitive && candidate.value.toRootLowerCase == header.value.toRootLowerCase => true
        case _ => false
      }

      def headerExists(candidate: HttpHeader, showExactOther: Boolean = true, caseInsensitive: Boolean = false): Expectation =
        check(_.headers.find(_.name == candidate.name))(compare(candidate, caseInsensitive), {
          case Some(other) if showExactOther => s"response that was missing required `$candidate` header. Found `$other` with the wrong value."
          case Some(_)                       => s"response with invalid `${candidate.name}` header."
          case None                          => s"response that was missing required `${candidate.name}` header."
        })

      val expectations: Expectation =
        check(_.status)(_ == StatusCodes.SwitchingProtocols, "unexpected status code: " + _) &&
          headerExists(UpgradeHeader, caseInsensitive = true) &&
          headerExists(ConnectionUpgradeHeader, caseInsensitive = true) &&
          headerExists(`Sec-WebSocket-Accept`.forKey(key), showExactOther = false)

      expectations(response) match {
        case None =>
          val subs = response.header[`Sec-WebSocket-Protocol`].flatMap(_.protocols.headOption)

          if (subprotocols.isEmpty && subs.isEmpty) Right(NegotiatedWebSocketSettings(None)) // no specific one selected
          else if (subs.nonEmpty && subprotocols.contains(subs.get)) Right(NegotiatedWebSocketSettings(Some(subs.get)))
          else Left(s"response that indicated that the given subprotocol was not supported. (client supported: ${subprotocols.mkString(", ")}, server supported: $subs)")
        case Some(problem) => Left(problem)
      }
    }
  }

  val UpgradeHeader = Upgrade(List(UpgradeProtocol("websocket")))
  val ConnectionUpgradeHeader = Connection(List("upgrade"))
  val SecWebSocketVersionHeader = `Sec-WebSocket-Version`(Seq(CurrentWebSocketVersion))
}
