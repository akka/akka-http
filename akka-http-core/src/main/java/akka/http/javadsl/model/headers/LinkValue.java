/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.Uri;
import akka.http.impl.util.Util;

public abstract class LinkValue {
    public abstract Uri getUri();
    public abstract Iterable<LinkParam> getParams();

    public static LinkValue create(Uri uri, LinkParam... params) {
        return new akka.http.scaladsl.model.headers.LinkValue(
                uri.asScala(),
                Util.<LinkParam, akka.http.scaladsl.model.headers.LinkParam>convertArray(params));
    }
}
