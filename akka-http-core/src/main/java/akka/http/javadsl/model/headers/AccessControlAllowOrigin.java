/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Allow-Origin` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-origin-response-header
 */
public abstract class AccessControlAllowOrigin extends akka.http.scaladsl.model.HttpHeader {
    public abstract HttpOriginRange range();

    public static AccessControlAllowOrigin wildcard() {
        return akka.http.scaladsl.model.headers.Access$minusControl$minusAllow$minusOrigin$.MODULE$.$times();
    }
    public static AccessControlAllowOrigin nullOrigin() {
        return create(HttpOriginRange.create());
    }

    public static AccessControlAllowOrigin create(HttpOriginRange range) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusAllow$minusOrigin(((akka.http.scaladsl.model.headers.HttpOriginRange) range));
    }
}
