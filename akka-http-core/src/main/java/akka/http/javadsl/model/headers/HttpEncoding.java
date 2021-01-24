/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class HttpEncoding {
    public abstract String value();

    public HttpEncodingRange toRange() {
        return HttpEncodingRange.create(this);
    }
}
