/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Remote-Address` header.
 *  Custom header we use for optionally transporting the peer's IP in an HTTP header.
 *
 *  Deprecated since 10.2.0: use the remote-header-attribute instead.
 */
@Deprecated
public abstract class RemoteAddress extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.RemoteAddress address();

    public static RemoteAddress create(akka.http.javadsl.model.RemoteAddress address) {
        return new akka.http.scaladsl.model.headers.Remote$minusAddress(((akka.http.scaladsl.model.RemoteAddress) address));
    }
}
