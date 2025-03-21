/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ClientTransport;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocketRequest;
import akka.http.javadsl.model.ws.WebSocketUpgradeResponse;
import akka.http.javadsl.settings.ClientConnectionSettings;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@SuppressWarnings("unused")
public class WebSocketClientExampleTest {

  // compile only test
  public void testSingleWebSocketRequest() {
    //#single-WebSocket-request
    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();
    final Http http = Http.get(system);

    // print each incoming text message
    // would throw exception on non strict or binary message
    final Sink<Message, CompletionStage<Done>> printSink =
      Sink.foreach((message) ->
        System.out.println("Got message: " + message.asTextMessage().getStrictText())
      );

    // send this as a message over the WebSocket
    final Source<Message, NotUsed> helloSource =
      Source.single(TextMessage.create("hello world"));

    // the CompletionStage<Done> is the materialized value of Sink.foreach
    // and it is completed when the stream completes
    final Flow<Message, Message, CompletionStage<Done>> flow =
      Flow.fromSinkAndSourceMat(printSink, helloSource, Keep.left());

    final Pair<CompletionStage<WebSocketUpgradeResponse>, CompletionStage<Done>> pair =
      http.singleWebSocketRequest(
        WebSocketRequest.create("ws://ws.ifelse.io"),
        flow,
        materializer
      );

    // The first value in the pair is a CompletionStage<WebSocketUpgradeResponse> that
    // completes when the WebSocket request has connected successfully (or failed)
    final CompletionStage<Done> connected = pair.first().thenApply(upgrade -> {
      // just like a regular http request we can access response status which is available via upgrade.response.status
      // status code 101 (Switching Protocols) indicates that server support WebSockets
      if (upgrade.response().status().equals(StatusCodes.SWITCHING_PROTOCOLS)) {
        return Done.getInstance();
      } else {
        throw new RuntimeException("Connection failed: " + upgrade.response().status());
      }
    });

    // the second value is the completion of the sink from above
    // in other words, it completes when the WebSocket disconnects
    final CompletionStage<Done> closed = pair.second();

    // in a real application you would not side effect here
    // and handle errors more carefully
    connected.thenAccept(done -> System.out.println("Connected"));
    closed.thenAccept(done -> System.out.println("Connection closed"));

    //#single-WebSocket-request
  }

  // compile only test
  public void halfClosedWebSocketClosingExample() {

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();
    final Http http = Http.get(system);

    //#half-closed-WebSocket-closing

    // we may expect to be able to to just tail
    // the server websocket output like this
    final Flow<Message, Message, NotUsed> flow =
      Flow.fromSinkAndSource(
        Sink.foreach(System.out::println),
        Source.empty());

    http.singleWebSocketRequest(
      WebSocketRequest.create("ws://example.com:8080/some/path"),
      flow,
      materializer);

    //#half-closed-WebSocket-closing
  }

  public void halfClosedWebSocketWorkingExample() {
    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();
    final Http http = Http.get(system);

    //#half-closed-WebSocket-working

    // using Source.maybe materializes into a completable future
    // which will allow us to complete the source later
    final Flow<Message, Message, CompletableFuture<Optional<Message>>> flow =
      Flow.fromSinkAndSourceMat(
        Sink.foreach(System.out::println),
        Source.maybe(),
        Keep.right());

    final Pair<CompletionStage<WebSocketUpgradeResponse>, CompletableFuture<Optional<Message>>> pair =
      http.singleWebSocketRequest(
        WebSocketRequest.create("ws://example.com:8080/some/path"),
        flow,
        materializer);

    // at some later time we want to disconnect
    pair.second().complete(Optional.empty());
    //#half-closed-WebSocket-working
  }

  public void halfClosedWebSocketFiniteWorkingExample() {
    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();
    final Http http = Http.get(system);

    //#half-closed-WebSocket-finite

    // emit "one" and then "two" and then keep the source from completing
    final Source<Message, CompletableFuture<Optional<Message>>> source =
      Source.from(Arrays.<Message>asList(TextMessage.create("one"), TextMessage.create("two")))
        .concatMat(Source.maybe(), Keep.right());

    final Flow<Message, Message, CompletableFuture<Optional<Message>>> flow =
      Flow.fromSinkAndSourceMat(
        Sink.foreach(System.out::println),
        source,
        Keep.right());

    final Pair<CompletionStage<WebSocketUpgradeResponse>, CompletableFuture<Optional<Message>>> pair =
      http.singleWebSocketRequest(
        WebSocketRequest.create("ws://example.com:8080/some/path"),
        flow,
        materializer);

    // at some later time we want to disconnect
    pair.second().complete(Optional.empty());
    //#half-closed-WebSocket-finite
  }



  // compile time only test
  public void testAuthorizedSingleWebSocketRequest() {
    Materializer materializer = null;
    Http http = null;

    Flow<Message, Message, NotUsed> flow = null;

    //#authorized-single-WebSocket-request
    http.singleWebSocketRequest(
      WebSocketRequest.create("ws://example.com:8080/some/path")
        .addHeader(Authorization.basic("johan", "correcthorsebatterystaple")),
      flow,
      materializer);
    //#authorized-single-WebSocket-request
  }

  // compile time only test
  public void testWebSocketClientFlow() {
    //#WebSocket-client-flow
    ActorSystem system = ActorSystem.create();
    Materializer materializer = SystemMaterializer.get(system).materializer();
    Http http = Http.get(system);

    // print each incoming text message
    // would throw exception on non strict or binary message
    Sink<Message, CompletionStage<Done>> printSink =
      Sink.foreach((message) ->
          System.out.println("Got message: " + message.asTextMessage().getStrictText())
      );

    // send this as a message over the WebSocket
    Source<Message, NotUsed> helloSource =
      Source.single(TextMessage.create("hello world"));


    Flow<Message, Message, CompletionStage<WebSocketUpgradeResponse>> webSocketFlow =
      http.webSocketClientFlow(WebSocketRequest.create("ws://ws.ifelse.io"));


    Pair<CompletionStage<WebSocketUpgradeResponse>, CompletionStage<Done>> pair =
      helloSource.viaMat(webSocketFlow, Keep.right())
        .toMat(printSink, Keep.both())
        .run(materializer);


    // The first value in the pair is a CompletionStage<WebSocketUpgradeResponse> that
    // completes when the WebSocket request has connected successfully (or failed)
    CompletionStage<WebSocketUpgradeResponse> upgradeCompletion = pair.first();

    // the second value is the completion of the sink from above
    // in other words, it completes when the WebSocket disconnects
    CompletionStage<Done> closed = pair.second();

    CompletionStage<Done> connected = upgradeCompletion.thenApply(upgrade->
    {
      // just like a regular http request we can access response status which is available via upgrade.response.status
      // status code 101 (Switching Protocols) indicates that server support WebSockets
      if (upgrade.response().status().equals(StatusCodes.SWITCHING_PROTOCOLS)) {
        return Done.getInstance();
      } else {
        throw new RuntimeException(("Connection failed: " + upgrade.response().status()));
      }
    });

    // in a real application you would not side effect here
    // and handle errors more carefully
    connected.thenAccept(done -> System.out.println("Connected"));
    closed.thenAccept(done -> System.out.println("Connection closed"));

    //#WebSocket-client-flow
  }

  // compile only test
  public void testSingleWebSocketRequestWithHttpsProxyExample() {
    //#https-proxy-singleWebSocket-request-example

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();

    final Flow<Message, Message, NotUsed> flow =
      Flow.fromSinkAndSource(
        Sink.foreach(System.out::println),
        Source.single(TextMessage.create("hello world")));

    ClientTransport proxy = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved("192.168.2.5", 8080));
    ClientConnectionSettings clientSettingsWithHttpsProxy = ClientConnectionSettings.create(system)
      .withTransport(proxy);

    Http.get(system)
      .singleWebSocketRequest(
        WebSocketRequest.create("wss://example.com:8080/some/path"),
        flow,
        Http.get(system).defaultClientHttpsContext(),
        null,
        clientSettingsWithHttpsProxy, // <- pass in the custom settings here
        system.log(),
        materializer);

    //#https-proxy-singleWebSocket-request-example
  }

  // compile only test
  public void testSingleWebSocketRequestWithHttpsProxyExampleWithAuth() {

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = SystemMaterializer.get(system).materializer();

    final Flow<Message, Message, NotUsed> flow =
      Flow.fromSinkAndSource(
        Sink.foreach(System.out::println),
        Source.single(TextMessage.create("hello world")));

    //#auth-https-proxy-singleWebSocket-request-example
    InetSocketAddress proxyAddress =
      InetSocketAddress.createUnresolved("192.168.2.5", 8080);
    HttpCredentials credentials =
      HttpCredentials.createBasicHttpCredentials("proxy-user", "secret-proxy-pass-dont-tell-anyone");

    ClientTransport proxy = ClientTransport.httpsProxy(proxyAddress, credentials); // include credentials
    ClientConnectionSettings clientSettingsWithHttpsProxy = ClientConnectionSettings.create(system)
      .withTransport(proxy);

    Http.get(system)
      .singleWebSocketRequest(
        WebSocketRequest.create("wss://example.com:8080/some/path"),
        flow,
        Http.get(system).defaultClientHttpsContext(),
        null,
        clientSettingsWithHttpsProxy, // <- pass in the custom settings here
        system.log(),
        materializer);

    //#auth-https-proxy-singleWebSocket-request-example
  }


}
