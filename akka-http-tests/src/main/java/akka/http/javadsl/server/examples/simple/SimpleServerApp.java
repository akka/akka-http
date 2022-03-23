/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;


import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.unmarshalling.Unmarshaller.entityToString;

//#https-http-config

//#https-http-config

public class SimpleServerApp {


  //#https-http-app
  public Route multiply(int x, int y) {
    int result = x * y;
    return complete(String.format("%d * %d = %d", x, y, result));
  }

  public CompletionStage<Route> multiplyAsync(Executor ctx, int x, int y) {
    return CompletableFuture.supplyAsync(() -> multiply(x, y), ctx);
  }

  public Route createRoute() {
    Route addHandler = parameter(StringUnmarshallers.INTEGER, "x", x ->
      parameter(StringUnmarshallers.INTEGER, "y", y -> {
        int result = x + y;
        return complete(String.format("%d + %d = %d", x, y, result));
      })
    );

    BiFunction<Integer, Integer, Route> subtractHandler = (x, y) -> {
      int result = x - y;
      return complete(String.format("%d - %d = %d", x, y, result));
    };

    return
      concat(
        // matches the empty path
        pathSingleSlash(() ->
          getFromResource("web/calculator.html")
        ),
        // matches paths like this: /add?x=42&y=23
        path("add", () -> addHandler),
        path("subtract", () ->
          parameter(StringUnmarshallers.INTEGER, "x", x ->
            parameter(StringUnmarshallers.INTEGER, "y", y ->
              subtractHandler.apply(x, y)
            )
          )
        ),
        // matches paths like this: /multiply/{x}/{y}
        path(PathMatchers.segment("multiply").slash(integerSegment()).slash(integerSegment()),
          this::multiply
        ),
        path(PathMatchers.segment("multiplyAsync").slash(integerSegment()).slash(integerSegment()), (x, y) ->
          extractExecutionContext(ctx ->
            onSuccess(multiplyAsync(ctx, x, y), Function.identity())
          )
        ),
        post(() ->
          path("hello", () ->
            entity(entityToString(), body ->
              complete("Hello " + body + "!")
            )
          )
        )
      );
  }

  // ** STARTING THE SERVER ** //

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create("SimpleServerApp");

    boolean useHttps = false; // pick value from anywhere

    final SimpleServerApp app = new SimpleServerApp();

    if ( useHttps ) {
        //#bind-low-level-context
        Http.get(system).newServerAt("localhost", 8080)
            .enableHttps(createHttpsContext(system))
            .bind(app.createRoute());
        //#bind-low-level-context
    } else {
        Http.get(system).newServerAt("localhost", 8080)
            .bind(app.createRoute());
    }

    System.out.println("Type RETURN to exit");
    System.in.read();
    system.terminate();
  }
  //#https-http-app

  //#https-http-config
  // ** CONFIGURING ADDITIONAL SETTINGS ** //

  public static HttpsConnectionContext createHttpsContext(ActorSystem system) {
      try {
        // initialise the keystore
        // !!! never put passwords into code !!!
        final char[] password = new char[]{'a', 'b', 'c', 'd', 'e', 'f'};

        final KeyStore ks = KeyStore.getInstance("PKCS12");
        final InputStream keystore = SimpleServerApp.class.getClassLoader().getResourceAsStream("httpsDemoKeys/keys/server.p12");
        if (keystore == null) {
          throw new RuntimeException("Keystore required!");
        }
        ks.load(keystore, password);

        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
        keyManagerFactory.init(ks, password);

        final TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return ConnectionContext.httpsServer(sslContext);

      } catch (Exception e) {
          throw new RuntimeException(e);
      }
  }
  //#https-http-config
}
