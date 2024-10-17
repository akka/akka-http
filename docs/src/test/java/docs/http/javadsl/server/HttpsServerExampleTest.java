/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.common.SSLContextFactory;
import akka.http.javadsl.server.Route;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import javax.net.ssl.*;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/* COMPILE ONLY TEST */
public class HttpsServerExampleTest extends JUnitSuite {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  void requireClientAuth() {
    final ActorSystem system = ActorSystem.create();
    SSLContext sslContext = null;
    //#require-client-auth
    ConnectionContext.httpsServer(() -> {
            SSLEngine engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);

            // to require trusted certs for any client to allow them to connect
            engine.setNeedClientAuth(true);
            // or, for optional client certs:
            // engine.setWantClientAuth(true);

            return engine;
    });
    //#require-client-auth
  }

  void convenienceCertLoad() {
    final ActorSystem system = ActorSystem.create();
    //#convenience-cert-loading
    HttpsConnectionContext https = ConnectionContext.httpsServer(SSLContextFactory.createSSLContextFromPem(
        Paths.get("/some/path/server.crt"),
        Paths.get("/some/path/server.key"),
        List.of(Paths.get("/some/path/serverCA.crt"))
    ));

    // or from a config block
    // my-server {
    //   certificate = "/some/path/server.crt"
    //   private-key = "/some/path/server.key"
    //   trusted-ca-certificates = ["/some/path/clientCA.crt"]
    //   # or to use the default trust store
    //   trusted-ca-certificates = "system"
    // }
    ConnectionContext.httpsServer(SSLContextFactory.createSSLContextFromPem(system.settings().config().getConfig("my-server")));
    //#convenience-cert-loading
  }

  void rotatingCert() {
    final ActorSystem system = ActorSystem.create();
    final Route routes = null;

    //#rotate-certs
    HttpsConnectionContext https = ConnectionContext.httpsServer(SSLContextFactory.refreshingSSLEngineProvider(
        Duration.ofMinutes(5),
        () ->
          SSLContextFactory.createSSLContextFromPem(
            Paths.get("/some/path/server.crt"),
            Paths.get("/some/path/server.key"),
            List.of(Paths.get("/some/path/serverCA.crt")))));
    Http.get(system).newServerAt("127.0.0.1", 443)
        .enableHttps(https)
        .bind(routes);
    //#rotate-certs
  }

}
