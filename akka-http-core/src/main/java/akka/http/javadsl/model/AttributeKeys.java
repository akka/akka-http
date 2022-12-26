/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.javadsl.model.ws.WebSocketUpgrade;

public final class AttributeKeys {
    public static final AttributeKey<RemoteAddress> remoteAddress =
            (AttributeKey<RemoteAddress>)(Object)akka.http.scaladsl.model.AttributeKeys.remoteAddress();
    public static final AttributeKey<WebSocketUpgrade> webSocketUpgrade =
            (AttributeKey<WebSocketUpgrade>)(Object)akka.http.scaladsl.model.AttributeKeys.webSocketUpgrade();
    public static final AttributeKey<SslSessionInfo> sslSession =
            (AttributeKey<SslSessionInfo>)(Object)akka.http.scaladsl.model.AttributeKeys.sslSession();
    public static final AttributeKey<Trailer> trailer =
            (AttributeKey<Trailer>)(Object)akka.http.scaladsl.model.AttributeKeys.trailer();
}
