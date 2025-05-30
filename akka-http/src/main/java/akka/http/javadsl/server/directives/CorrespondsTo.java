/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.annotation.InternalApi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * INTERNAL API – used for consistency specs
 *
 * Used to hint at consistency spec implementations that a given JavaDSL method corresponds
 * to a method of given name in ScalaDSL.
 *
 * E.g. a Java method paramsList could be hinted using <code>@CorrespondsTo("paramsSeq")</code>.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@InternalApi
public @interface CorrespondsTo {
  String value();
}
