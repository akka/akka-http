/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.headers;

public abstract class BasicHttpCredentials extends akka.http.scaladsl.model.headers.HttpCredentials {
    public abstract String username();
    public abstract String password();
}
