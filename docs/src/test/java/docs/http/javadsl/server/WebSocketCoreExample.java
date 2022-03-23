/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#websocket-example-using-core

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import akka.NotUsed;
import akka.http.impl.util.JavaMapping;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.ws.WebSocketRequest;
import akka.http.javadsl.settings.ClientConnectionSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.settings.WebSocketSettings;
import akka.http.scaladsl.model.AttributeKeys;
import akka.japi.JavaPartialFunction;
import akka.japi.function.Function;

import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocket;
import akka.util.ByteString;

@SuppressWarnings({"Convert2MethodRef", "ConstantConditions"})
public class WebSocketCoreExample {

  //#websocket-handling
  public static HttpResponse handleRequest(HttpRequest request) {
    System.out.println("Handling request to " + request.getUri());

    if (request.getUri().path().equals("/greeter")) {
      return request.getAttribute(AttributeKeys.webSocketUpgrade())
        .map(upgrade -> {
          Flow<Message, Message, NotUsed> greeterFlow = greeter();
          HttpResponse response = upgrade.handleMessagesWith(greeterFlow);
          return response;
        })
        .orElse(
          HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected WebSocket request")
        );
    } else {
      return HttpResponse.create().withStatus(404);
    }
  }
  //#websocket-handling

  public static void main(String[] args) throws Exception {
    ActorSystem system = ActorSystem.create();

    try {
      final Function<HttpRequest, HttpResponse> handler = request -> handleRequest(request);
      CompletionStage<ServerBinding> serverBindingFuture =
          Http.get(system)
              .newServerAt("localhost", 8080)
              .bindSync(handler);

      // will throw if binding fails
      serverBindingFuture.toCompletableFuture().get(1, TimeUnit.SECONDS);
      System.out.println("Press ENTER to stop.");
      new BufferedReader(new InputStreamReader(System.in)).readLine();
    } finally {
      system.terminate();
    }
  }

  //#websocket-handler

  /**
   * A handler that treats incoming messages as a name,
   * and responds with a greeting to that name
   */
  public static Flow<Message, Message, NotUsed> greeter() {
    return
      Flow.<Message>create()
        .collect(new JavaPartialFunction<Message, Message>() {
          @Override
          public Message apply(Message msg, boolean isCheck) throws Exception {
            if (isCheck) {
              if (msg.isText()) {
                return null;
              } else {
                throw noMatch();
              }
            } else {
              return handleTextMessage(msg.asTextMessage());
            }
          }
        });
  }

  public static TextMessage handleTextMessage(TextMessage msg) {
    if (msg.isStrict()) // optimization that directly creates a simple response...
    {
      return TextMessage.create("Hello " + msg.getStrictText());
    } else // ... this would suffice to handle all text messages in a streaming fashion
    {
      return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
  }
  //#websocket-handler

  {
    ActorSystem system = null;
    Flow<HttpRequest, HttpResponse, NotUsed> handler = null;
    //#websocket-ping-payload-server
    ServerSettings defaultSettings = ServerSettings.create(system);

    AtomicInteger pingCounter = new AtomicInteger();

    WebSocketSettings customWebsocketSettings = defaultSettings.getWebsocketSettings()
        .withPeriodicKeepAliveData(() ->
            ByteString.fromString(String.format("debug-%d", pingCounter.incrementAndGet()))
        );


    ServerSettings customServerSettings = defaultSettings.withWebsocketSettings(customWebsocketSettings);

    Http http = Http.get(system);
    http.newServerAt("127.0.0.1", 8080)
        .withSettings(customServerSettings)
        .bindFlow(handler);
    //#websocket-ping-payload-server
  }

  {
    ActorSystem system = null;
    Materializer materializer = null;
    Flow<Message, Message, NotUsed> clientFlow = null;
    //#websocket-client-ping-payload
    ClientConnectionSettings defaultSettings = ClientConnectionSettings.create(system);

    AtomicInteger pingCounter = new AtomicInteger();

    WebSocketSettings customWebsocketSettings = defaultSettings.getWebsocketSettings()
        .withPeriodicKeepAliveData(() ->
            ByteString.fromString(String.format("debug-%d", pingCounter.incrementAndGet()))
        );

    ClientConnectionSettings customSettings =
        defaultSettings.withWebsocketSettings(customWebsocketSettings);

    Http http = Http.get(system);
    http.singleWebSocketRequest(
        WebSocketRequest.create("ws://127.0.0.1"),
        clientFlow,
        ConnectionContext.noEncryption(),
        Optional.empty(),
        customSettings,
        system.log(),
        materializer
    );
    //#websocket-client-ping-payload
  }
}
//#websocket-example-using-core
