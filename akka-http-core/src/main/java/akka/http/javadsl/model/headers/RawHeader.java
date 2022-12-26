/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.annotation.DoNotInherit;

/**
 *  A header in its 'raw' name/value form, not parsed into a modelled header class.
 *  To add a custom header type, implement {@link ModeledCustomHeader} and {@link ModeledCustomHeaderFactory}
 *  rather than extending {@link RawHeader}
 */
@DoNotInherit
public abstract class RawHeader extends akka.http.scaladsl.model.HttpHeader {
    public abstract String name();
    public abstract String value();

    public static RawHeader create(String name, String value) {
        return new akka.http.scaladsl.model.headers.RawHeader(name, value);
    }
}
