/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.javadsl.model.sse;

import java.util.Optional;
import java.util.OptionalInt;

import akka.http.javadsl.model.sse.ServerSentEvent;
import org.scalatest.junit.JUnitSuite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

public class ServerSentEventTest extends JUnitSuite {

    @Test
    public void create() {
        final ServerSentEvent event = ServerSentEvent.create(
                "data",
                Optional.of("type"),
                Optional.of("id"),
                OptionalInt.of(1000)
        );
        Assertions.assertEquals("data", event.getData());
        Assertions.assertEquals(Optional.of("type"), event.getEventType());
        Assertions.assertEquals(Optional.of("id"), event.getId());
        Assertions.assertEquals(OptionalInt.of(1000), event.getRetry());
    }

    @Test
    public void createData() {
        final ServerSentEvent event = ServerSentEvent.create("data");
        Assertions.assertEquals("data", event.getData());
    }

    @Test
    public void createDataEvent() {
        final ServerSentEvent event = ServerSentEvent.create("data", "type");
        Assertions.assertEquals("data", event.getData());
        Assertions.assertEquals(Optional.of("type"), event.getEventType());
    }

    @Test
    public void createDataEventId() {
        final ServerSentEvent event = ServerSentEvent.create("data", "type", "id");
        Assertions.assertEquals("data", event.getData());
        Assertions.assertEquals(Optional.of("type"), event.getEventType());
        Assertions.assertEquals(Optional.of("id"), event.getId());
    }

    @Test
    public void createRetry() {
        final ServerSentEvent event = ServerSentEvent.create("data", 1000);
        Assertions.assertEquals(OptionalInt.of(1000), event.getRetry());
    }
}
