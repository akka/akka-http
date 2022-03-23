/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 * The model of an HTTP header. In its most basic form headers are simple name-value pairs. Header names
 * are compared in a case-insensitive way.
 *
 * Implement {@link ModeledCustomHeader} and {@link ModeledCustomHeaderFactory} instead of {@link CustomHeader} to be
 * able to use the convenience methods that allow parsing the custom user-defined header from {@link akka.http.javadsl.model.HttpHeader}.
 */
public abstract class CustomHeader extends akka.http.scaladsl.model.HttpHeader {
    public abstract String name();
    public abstract String value();
}
