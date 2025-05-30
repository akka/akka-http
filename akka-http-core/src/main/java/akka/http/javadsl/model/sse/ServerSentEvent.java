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

import akka.annotation.DoNotInherit;
import akka.util.ByteString;
import scala.Option;
import scala.jdk.javaapi.OptionConverters;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Representation of a server-sent event. According to the specification, an empty data field
 * designates an event which is to be ignored which is useful for heartbeats.
 * <p>
 * Not for user extension
 */
@DoNotInherit
public abstract class ServerSentEvent {

    private static final Option<String> stringNone = Option.empty();

    private static final Option<Object> intNone = Option.empty();


    /**
     * Provides a [[ServerSentEvent]] with empty data which can be used as a heartbeat
     */
    public static ServerSentEvent heartbeat() {
        return akka.http.scaladsl.model.sse.ServerSentEvent.heartbeat();
    }

    /**
     * Creates a [[ServerSentEvent]].
     *
     * @param data data, may be empty or span multiple lines
     */
    public static ServerSentEvent create(String data) {
        return akka.http.scaladsl.model.sse.ServerSentEvent.apply(data, stringNone, stringNone, intNone);
    }

    /**
     * Creates a [[ServerSentEvent]].
     *
     * @param data data, may span multiple lines
     * @param type type, must not contain \n or \r
     */
    public static ServerSentEvent create(String data, String type) {
        return akka.http.scaladsl.model.sse.ServerSentEvent.apply(data, type);
    }

    /**
     * Creates a [[ServerSentEvent]].
     *
     * @param data data, may span multiple lines
     * @param type type, must not contain \n or \r
     * @param id id, must not contain \n or \r
     */
    public static ServerSentEvent create(String data, String type, String id) {
        return akka.http.scaladsl.model.sse.ServerSentEvent.apply(data, type, id);
    }

    /**
     * Creates a [[ServerSentEvent]].
     *
     * @param data data, may span multiple lines
     * @param retry reconnection delay in milliseconds
     */
    public static ServerSentEvent create(String data, int retry) {
        return akka.http.scaladsl.model.sse.ServerSentEvent.apply(data, retry);
    }

    /**
     * Creates a [[ServerSentEvent]].
     *
     * @param data data, may span multiple lines
     * @param type optional type, must not contain \n or \r
     * @param id optional id, must not contain \n or \r
     * @param retry optional reconnection delay in milliseconds
     */
    public static ServerSentEvent create(String data,
                                         Optional<String> type,
                                         Optional<String> id,
                                         OptionalInt retry) {
        return akka.http.scaladsl.model.sse.ServerSentEvent.apply(
                data, OptionConverters.toScala(type), OptionConverters.toScala(id), OptionConverters.toScala(retry).map(retryInt -> (Object) retryInt)
        );
    }

    /**
     * Data, may span multiple lines.
     */
    public abstract String getData();

    /**
     * Optional type, must not contain \n or \r.
     */
    public abstract Optional<String> getEventType();

    /**
     * Optional id, must not contain \n or \r.
     */
    public abstract Optional<String> getId();

    /**
     * Optional reconnection delay in milliseconds.
     */
    public abstract OptionalInt getRetry();

    /**
     * Encode the event to bytes for use in a response
     */
    public abstract ByteString encode();
}
