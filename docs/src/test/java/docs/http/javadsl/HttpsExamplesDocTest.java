/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

@SuppressWarnings("unused")
public class HttpsExamplesDocTest {

  // compile only test
  public void testConstructRequest() {
    String unsafeHost = "example.com";
    //#disable-hostname-verification-connection
    final ActorSystem system = ActorSystem.create();
    final Http http = Http.get(system);

    final HttpsConnectionContext badCtx = ConnectionContext.httpsClient((host, port) -> {
      SSLEngine engine = SSLContext.getDefault().createSSLEngine(host, port);
      engine.setUseClientMode(true);

      // WARNING: this creates an SSL Engine without enabling endpoint identification/verification procedures
      // Disabling host name verification is a very bad idea, please don't unless you have a very good reason to.
      // When in doubt, use the `ConnectionContext.httpsClient` that takes an `SSLContext` instead, or enable
      // with:
      // SSLParameters params = engine.getSSLParameters();
      // params.setEndpointIdentificationAlgorithm("https");
      // engine.setSSLParameters(params);

      return engine;
    });

    http.connectionTo(unsafeHost).withCustomHttpsConnectionContext(badCtx).https();
    //#disable-hostname-verification-connection
  }

}
