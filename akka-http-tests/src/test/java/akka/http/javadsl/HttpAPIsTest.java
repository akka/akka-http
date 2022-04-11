/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.event.LoggingAdapter;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.settings.ClientConnectionSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.settings.ConnectionPoolSettings;
import akka.japi.function.Function;
import akka.stream.javadsl.Flow;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.ConnectHttp.toHost;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

@SuppressWarnings("ConstantConditions")
public class HttpAPIsTest extends JUnitRouteTest {

  @Test
  public void placeholderCompileTimeOnlyTest() {
    // fails if there are no test cases
  }

  @SuppressWarnings("unused")
  public void compileOnly() throws Exception {
    final Http http = Http.get(system());

    final ConnectionContext connectionContext = ConnectionContext.https(SSLContext.getDefault());
    final HttpConnectionContext httpContext = ConnectionContext.noEncryption();
    final HttpsConnectionContext httpsContext = ConnectionContext.https(SSLContext.getDefault());

    String host = "";
    int port = 9090;
    ConnectionPoolSettings conSettings = null;
    LoggingAdapter log = null;

    http.newServerAt("127.0.0.1", 8080).connectionSource();
    http.newServerAt("127.0.0.1", 8080).enableHttps(httpsContext).connectionSource();

    final Flow<HttpRequest, HttpResponse, ?> handler = null;
    http.newServerAt("127.0.0.1", 8080).bindFlow(handler);
    http.newServerAt("127.0.0.1", 8080).enableHttps(httpsContext).bindFlow(handler);

    final Function<HttpRequest, CompletionStage<HttpResponse>> handler1 = null;
    http.newServerAt("127.0.0.1", 8080).bind(handler1);
    http.newServerAt("127.0.0.1", 8080).enableHttps(httpsContext).bind(handler1);

    final Function<HttpRequest, HttpResponse> handler2 = null;
    http.newServerAt("127.0.0.1", 8080).bindSync(handler2);
    http.newServerAt("127.0.0.1", 8080).enableHttps(httpsContext).bindSync(handler2);

    final HttpRequest handler3 = null;
    http.singleRequest(handler3);
    http.singleRequest(handler3, httpsContext);
    http.singleRequest(handler3, httpsContext, conSettings, log);

    http.outgoingConnection("akka.io");
    http.outgoingConnection("akka.io:8080");
    http.outgoingConnection("https://akka.io");
    http.outgoingConnection("https://akka.io:8081");

    http.outgoingConnection(toHost("akka.io"));
    http.outgoingConnection(toHost("akka.io", 8080));
    http.outgoingConnection(toHost("https://akka.io"));
    http.outgoingConnection(toHostHttps("akka.io")); // default ssl context (ssl-config)
    http.outgoingConnection(toHostHttps("ssh://akka.io")); // throws, we explicitly require https or ""
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext));
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext());
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext());

    http.connectionTo("akka.io").http();
    http.connectionTo("akka.io").https();
    http.connectionTo("akka.io").http2();
    http.connectionTo("akka.io").http2WithPriorKnowledge();
    http.connectionTo("akka.io")
        .toPort(8081)
        .withCustomHttpsConnectionContext(httpsContext)
        .withClientConnectionSettings(ClientConnectionSettings.create(ConfigFactory.empty()))
        .logTo(system().log())
        .https();

    // in future we can add modify(context -> Context) to "keep ssl-config defaults, but tweak them in code)

    http.newHostConnectionPool("akka.io", materializer());
    http.newHostConnectionPool("https://akka.io", materializer());
    http.newHostConnectionPool("https://akka.io:8080", materializer());
    http.newHostConnectionPool(toHost("akka.io"), materializer());
    http.newHostConnectionPool(toHostHttps("ftp://akka.io"), materializer()); // throws, we explicitly require https or ""
    http.newHostConnectionPool(toHostHttps("https://akka.io:2222"), materializer());
    http.newHostConnectionPool(toHostHttps("akka.io"), materializer());
    http.newHostConnectionPool(toHost(""), conSettings, log, materializer());


    http.cachedHostConnectionPool("akka.io");
    http.cachedHostConnectionPool("https://akka.io");
    http.cachedHostConnectionPool("https://akka.io:8080");
    http.cachedHostConnectionPool(toHost("akka.io"));
    http.cachedHostConnectionPool(toHostHttps("smtp://akka.io")); // throws, we explicitly require https or ""
    http.cachedHostConnectionPool(toHostHttps("https://akka.io:2222"));
    http.cachedHostConnectionPool(toHostHttps("akka.io"));
    http.cachedHostConnectionPool(toHost("akka.io"), conSettings, log);

    http.superPool();
    http.superPool(conSettings, log);
    http.superPool(conSettings, httpsContext, log);

    final ConnectWithHttps connect = toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext();
    connect.effectiveHttpsConnectionContext(http.defaultClientHttpsContext()); // usage by us internally
  }

  @SuppressWarnings("unused")
  public void compileOnlyBinding() throws Exception {
    final Http http = Http.get(system());
    final HttpsConnectionContext httpsConnectionContext = null;

    http.bind(toHost("127.0.0.1")); // 80
    http.bind(toHost("127.0.0.1", 8080)); // 8080

    http.bind(toHost("https://127.0.0.1")); // HTTPS 443
    http.bind(toHost("https://127.0.0.1", 9090)); // HTTPS 9090

    http.bind(toHostHttps("127.0.0.1")); // HTTPS 443
    http.bind(toHostHttps("127.0.0.1").withCustomHttpsContext(httpsConnectionContext)); // custom HTTPS 443

    http.bind(toHostHttps("http://127.0.0.1")); // throws
  }
}
