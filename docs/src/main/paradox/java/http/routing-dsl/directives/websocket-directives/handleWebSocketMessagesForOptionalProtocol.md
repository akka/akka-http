# handleWebSocketMessagesForOptionalProtocol

## Description

Handles WebSocket requests with the given handler and rejects other requests with an
@javadoc:[ExpectedWebSocketRequestRejection](akka.http.javadsl.server.ExpectedWebSocketRequestRejection$).

If the `subprotocol` parameter is @javadoc:[empty](java.util.Optional#empty--) any WebSocket request is accepted. If the `subprotocol` parameter is
a non-empty @javadoc:[Optional](java.util.Optional) a WebSocket request is only accepted if the list of subprotocols supported by the client (as
announced in the WebSocket request) matches the contained subprotocol. If the client did not offer the protocol in question
the request is rejected with an @javadoc:[UnsupportedWebSocketSubprotocolRejection](akka.http.javadsl.server.UnsupportedWebSocketSubprotocolRejection).

To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.

The `handleWebSocketMessagesForOptionalProtocol` directive is used as a building block for @ref[WebSocket Directives](index.md) to handle websocket messages.

For more information about the WebSocket support, see @ref[Server-Side WebSocket Support](../../../server-side/websocket-support.md#server-side-websocket-support-java).
