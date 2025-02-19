/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

import akka.http.javadsl.model.headers.HttpEncodingRanges;

import scala.jdk.javaapi.OptionConverters;

public abstract class RemoteAddress {
    public abstract boolean isUnknown();

    public abstract Optional<InetAddress> getAddress();

    /**
     * Returns a port if defined or 0 otherwise.
     */
    public abstract int getPort();

    public static RemoteAddress create(InetAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address, OptionConverters.toScala(Optional.empty()));
    }
    public static RemoteAddress create(InetSocketAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
    public static RemoteAddress create(byte[] address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
}
