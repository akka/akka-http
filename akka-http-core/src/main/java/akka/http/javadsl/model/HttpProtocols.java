/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

/** Contains constants of the supported Http protocols. */
public final class HttpProtocols {
  private HttpProtocols() {}

  public static final HttpProtocol HTTP_1_0 =
      akka.http.scaladsl.model.HttpProtocols.HTTP$div1$u002E0();
  public static final HttpProtocol HTTP_1_1 =
      akka.http.scaladsl.model.HttpProtocols.HTTP$div1$u002E1();
}
