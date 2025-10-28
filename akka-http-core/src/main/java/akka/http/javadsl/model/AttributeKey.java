/*
 * Copyright (C) 2019-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model;

import akka.annotation.DoNotInherit;

@DoNotInherit
public abstract class AttributeKey<T> {
    public static <U> AttributeKey<U> create(String name, Class<U> clazz) {
        return new akka.http.scaladsl.model.AttributeKey<U>(name, clazz);
    }
}
