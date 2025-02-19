/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.Uri;

/**
 *  Model for the `Location` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.1.2
 */
public abstract class Location extends akka.http.scaladsl.model.HttpHeader {
    public abstract Uri getUri();

    public static Location create(Uri uri) {
        return new akka.http.scaladsl.model.headers.Location(uri.asScala());
    }
    public static Location create(String uri) {
        return create(Uri.create(uri));
    }
}
