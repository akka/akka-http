/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class LinkParam {
    public abstract String key();
    public abstract Object value();
}
