/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.headers;

public abstract class HttpEncoding {
    public abstract String value();

    public HttpEncodingRange toRange() {
        return HttpEncodingRange.create(this);
    }
}
