/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.Uri;

/**
 *  Model for the `ContentLocation` header.
 *  Specification: https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
 */
public abstract class ContentLocation extends akka.http.scaladsl.model.HttpHeader {
    public abstract Uri getUri();

    public static ContentLocation create(Uri uri) {
        return new akka.http.scaladsl.model.headers.Content$minusLocation(uri.asScala());
    }
    public static ContentLocation create(String uri) {
        return create(Uri.create(uri));
    }
}
