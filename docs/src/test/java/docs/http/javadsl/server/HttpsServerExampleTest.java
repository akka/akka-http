/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import com.typesafe.sslconfig.akka.AkkaSSLConfig;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

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
}
