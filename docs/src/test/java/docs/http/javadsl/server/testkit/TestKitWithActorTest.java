/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

//#testkit-actor-integration
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Adapter;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.testkit.JUnitRouteTest;

import akka.http.javadsl.testkit.TestRoute;
import akka.http.javadsl.testkit.TestRouteResult;
import org.junit.Test;

public class TestKitWithActorTest extends JUnitRouteTest {

    @Test
    public void returnPongForGetPing() {
        // This test does not use the classic APIs,
        // so it needs to adapt the system:
        ActorSystem<Void> system = Adapter.toTyped(system());

        TestProbe<MyAppWithActor.Ping> probe = TestProbe.create(system);
        TestRoute testRoute = testRoute(new MyAppWithActor().createRoute(probe.getRef(), system.scheduler()));

        TestRouteResult result = testRoute.run(HttpRequest.GET("/ping"));
        MyAppWithActor.Ping ping = probe.expectMessageClass(MyAppWithActor.Ping.class);
        ping.replyTo.tell("PONG!");
        result.assertEntity("PONG!");
    }
}
//#testkit-actor-integration
