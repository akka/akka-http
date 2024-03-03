/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.common.SSLContextUtils;
import akka.http.javadsl.server.Route;
import akka.japi.Creator;
import akka.pki.pem.DERPrivateKeyLoader;
import akka.pki.pem.PEMDecoder;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

            engine.setNeedClientAuth(true);
            // or: engine.setWantClientAuth(true);

            return engine;
    });
    //#require-client-auth
  }

  void convenienceCertLoad() {
    final ActorSystem system = ActorSystem.create();
    //#convenience-cert-loading
    HttpsConnectionContext https = ConnectionContext.httpsServer(SSLContextUtils.constructSSLContext(
        Paths.get("/some/path/server.crt"),
        Paths.get("/some/path/server.key"),
        List.of(Paths.get("/some/path/serverCA.crt"))
    ));

    // or from a config block
    // my-server {
    //   certificate = "/some/path/server.crt"
    //   private-key = "/some/path/server.key"
    //   ca-certificates = ["/some/path/serverCA.crt"]
    // }
    ConnectionContext.httpsServer(SSLContextUtils.constructSSLContext(system.settings().config().getConfig("my-server")));
    //#convenience-cert-loading
  }

  void rotatingCert() {
    final ActorSystem system = ActorSystem.create();
    final Route routes = null;

    //#rotate-certs
    HttpsConnectionContext https = ConnectionContext.httpsServer(SSLContextUtils.refreshingSSLEngineProvider(
        Duration.ofMinutes(5),
        () ->
          SSLContextUtils.constructSSLContext(
            Paths.get("/some/path/server.crt"),
            Paths.get("/some/path/server.key"),
            List.of(Paths.get("/some/path/serverCA.crt")))));
    Http.get(system).newServerAt("127.0.0.1", 443)
        .enableHttps(https)
        .bind(routes);
    //#rotate-certs
  }

}
