/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.headers;

public abstract class LinkParam {
    public abstract String key();
    public abstract Object value();
}
