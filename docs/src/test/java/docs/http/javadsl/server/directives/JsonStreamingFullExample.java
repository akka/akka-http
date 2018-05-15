/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

//#custom-content-type
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;

import java.util.Random;
import java.util.stream.Stream;

public class JsonStreamingFullExample extends AllDirectives {

    public Route createRoute() {
        final MediaType.WithFixedCharset mediaType =
                MediaTypes.applicationWithFixedCharset("vnd.example.api.v1+json", HttpCharsets.UTF_8);

        final ContentType.WithFixedCharset contentType = ContentTypes.create(mediaType);

        final Marshaller<User, RequestEntity> userMarshaller =
                Marshaller.withFixedContentType(contentType, (User user) -> HttpEntities.create(contentType, user.toJson()));

        final EntityStreamingSupport jsonStreamingSupport = EntityStreamingSupport.json()
                .withContentType(contentType)
                .withParallelMarshalling(10, false);

        return get(() ->
                pathPrefix("users", () ->
                        completeOKWithSource(fetchUsers(), userMarshaller, jsonStreamingSupport)
                )
        );
    }

    private Source<User, NotUsed> fetchUsers() {
        final Random rnd = new Random();
        return Source.fromIterator(() -> Stream.generate(rnd::nextInt).map(this::dummyUser).limit(10000).iterator());
    }

    private User dummyUser(int id) {
        return new User(id, "User " + id);
    }

    static final class User {
        int id;
        String name;

        User(int id, String name) {
            this.id = id;
            this.name = name;
        }

        String toJson() {
            return "{\"id\":\"" + id + "\", \"name\":\"" + name + "\"}";
        }
    }

    public static void main(String[] args) {
        ActorSystem system = ActorSystem.create();
        final JsonStreamingFullExample app = new JsonStreamingFullExample();
        final Http http = Http.get(system);
        final ActorMaterializer materializer = ActorMaterializer.create(system);

        final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
        http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
    }
}
//#custom-content-type
