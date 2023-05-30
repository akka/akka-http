/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocketRequest;
import akka.japi.function.Function;
import akka.stream.Materializer;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class WSEchoTestClientApp {
    private static final Function<Message, String> messageStringifier = new Function<Message, String>() {
        private static final long serialVersionUID = 1L;
        @Override
        public String apply(Message msg) throws Exception {
            if (msg.isText() && msg.asTextMessage().isStrict())
                return msg.asTextMessage().getStrictText();
            else
                throw new IllegalArgumentException("Unexpected message "+msg);
        }
    };

    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        try {
            final Materializer materializer = SystemMaterializer.get(system).materializer();

            final Callable<Future<Message>> ignoredMessage = () -> Future.successful(TextMessage.create("blub"));
            final Future<Message> delayedCompletion =
                akka.pattern.Patterns.after(
                        FiniteDuration.apply(1, "second"),
                        system.scheduler(),
                        system.dispatcher(),
                        ignoredMessage);

            Source<Message, NotUsed> echoSource =
                Source.from(Arrays.<Message>asList(
                        TextMessage.create("abc"),
                        TextMessage.create("def"),
                        TextMessage.create("ghi")
                )).concat(Source.future(delayedCompletion).drop(1));

            Sink<Message, CompletionStage<List<String>>> echoSink =
                Flow.of(Message.class)
                    .map(messageStringifier)
                    .grouped(1000)
                    .toMat(Sink.<List<String>>head(), Keep.right());

            Flow<Message, Message, CompletionStage<List<String>>> echoClient =
                Flow.fromSinkAndSourceMat(echoSink, echoSource, Keep.left());

            CompletionStage<List<String>> result =
                Http.get(system).singleWebSocketRequest(
                    WebSocketRequest.create("ws://ws.ifelse.io"),
                    echoClient,
                    materializer
                ).second();

            List<String> messages = result.toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println("Collected " + messages.size() + " messages:");
            for (String msg: messages)
                System.out.println(msg);
        } finally {
            system.terminate();
        }
    }
}
