/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.scaladsl.model.ContentRange$;

import java.util.Optional;
import java.util.OptionalLong;

import scala.Option;

public abstract class ContentRange {
    public abstract boolean isByteContentRange();
    public abstract boolean isSatisfiable();
    public abstract boolean isOther();

    public abstract OptionalLong getSatisfiableFirst();
    public abstract OptionalLong getSatisfiableLast();

    public abstract Optional<String> getOtherValue();

    public abstract OptionalLong getInstanceLength();

    public static ContentRange create(long first, long last) {
        return ContentRange$.MODULE$.apply(first, last);
    }
    public static ContentRange create(long first, long last, long instanceLength) {
        return ContentRange$.MODULE$.apply(first, last, instanceLength);
    }
    @SuppressWarnings("unchecked")
    public static ContentRange create(long first, long last, OptionalLong instanceLength) {
        return ContentRange$.MODULE$.apply(first, last, (instanceLength.isPresent() ? Option.apply(instanceLength.getAsLong()) : Option.empty()));
    }
    public static ContentRange createUnsatisfiable(long length) {
        return new akka.http.scaladsl.model.ContentRange.Unsatisfiable(length);
    }
    public static ContentRange createOther(String value) {
        return new akka.http.scaladsl.model.ContentRange.Other(value);
    }
}
