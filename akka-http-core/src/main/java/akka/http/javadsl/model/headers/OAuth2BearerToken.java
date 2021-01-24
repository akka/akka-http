/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class OAuth2BearerToken extends akka.http.scaladsl.model.headers.HttpCredentials {
    public abstract String token();
}
