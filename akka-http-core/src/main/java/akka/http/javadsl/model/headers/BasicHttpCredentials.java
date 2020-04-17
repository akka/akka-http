/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class BasicHttpCredentials
    extends akka.http.scaladsl.model.headers.HttpCredentials {
  public abstract String username();

  public abstract String password();
}
