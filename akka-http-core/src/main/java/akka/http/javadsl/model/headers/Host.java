/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.Authority;

import java.net.InetSocketAddress;

public abstract class Host extends akka.http.scaladsl.model.HttpHeader {

    public static Host create(InetSocketAddress address) {
        return akka.http.scaladsl.model.headers.Host.apply(address);
    }

    public static Host create(String host) {
        return akka.http.scaladsl.model.headers.Host.apply(host);
    }

    public static Host create(String host, int port) {
      return akka.http.scaladsl.model.headers.Host.apply(host, port);
    }

    public static Host create(Authority authority) {
      return akka.http.scaladsl.model.headers.Host.apply((akka.http.scaladsl.model.Uri.Authority)authority);
    }

    public abstract akka.http.javadsl.model.Host host();
    public abstract int port();
}
