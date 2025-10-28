/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model;

/**
 * @see RequestEntityAcceptances for convenience access to often used values.
 * Do not extend this to a concrete Java class,
 * as implementation of RequestEntityAcceptation should only exist in Scala
 */
public abstract class RequestEntityAcceptance {
  public abstract boolean isEntityAccepted();
}
