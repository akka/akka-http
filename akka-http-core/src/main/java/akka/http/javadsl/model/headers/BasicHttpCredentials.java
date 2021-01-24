/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class BasicHttpCredentials extends akka.http.scaladsl.model.headers.HttpCredentials {
    public abstract String username();
    public abstract String password();
}
