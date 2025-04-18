/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.actor.ClassicActorSystemProvider;
import akka.annotation.DoNotInherit;
import akka.stream.FlowShape;
import akka.stream.Graph;
import akka.stream.Materializer;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.util.ByteString;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The base type for an Http message (request or response).
 *
 * INTERNAL API: this trait will be changed in binary-incompatible ways for classes that are derived from it!
 * Do not implement this interface outside the Akka code base!
 *
 * Binary compatibility is only maintained for callers of this trait’s interface.
 */
@DoNotInherit
public interface HttpMessage {
    /**
     * Is this instance a request.
     */
    boolean isRequest();

    /**
     * Is this instance a response.
     */
    boolean isResponse();

    /**
     * The protocol of this message.
     */
    HttpProtocol protocol();

    /**
     * An iterable containing the headers of this message.
     */
    Iterable<HttpHeader> getHeaders();

    /**
     * Try to find the first header with the given name (case-insensitive) and return
     * Optional.of(header), otherwise this method returns an empty Optional.
     */
    Optional<HttpHeader> getHeader(String headerName);

    /**
     * Try to find the first header of the given class and return
     * Optional.of(header), otherwise this method returns an empty Optional.
     *
     * @throws IllegalArgumentException if headerClass is a custom header.
     */
    <T extends HttpHeader> Optional<T> getHeader(Class<T> headerClass);

    /**
     * An iterable containing all headers of the given class
     * of this message.
     */
    <T extends HttpHeader> Iterable<T> getHeaders(Class<T> headerClass);

    /**
     * Try to find the attribute for the given key and return
     * Optional.of(attribute), otherwise this method returns an empty Optional.
     */
    <T> Optional<T> getAttribute(AttributeKey<T> key);

    /**
     * The entity of this message.
     */
    ResponseEntity entity();

    /**
     * Discards the entities data bytes by running the {@code dataBytes} Source contained by the {@code entity}
     * of this HTTP message.
     *
     * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.javadsl.Source]]
     * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
     * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
     * just having ignored the data.
     *
     * Warning: It is not allowed to discard and/or consume the {@code entity.dataBytes} more than once
     * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
     * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
     * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
     * "stream can cannot be materialized more than once" exception.
     *
     * When called on `Strict` entities or sources whose values can be buffered in memory,
     * the above warnings can be ignored. Repeated materialization is not necessary in this case, avoiding
     * the mentioned exceptions due to the data being held in memory.
     *
     * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
     */
    DiscardedEntity discardEntityBytes(Materializer materializer);

    /**
     * Discards the entities data bytes by running the {@code dataBytes} Source contained by the {@code entity}
     * of this HTTP message.
     *
     * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.javadsl.Source]]
     * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
     * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
     * just having ignored the data.
     *
     * Warning: It is not allowed to discard and/or consume the {@code entity.dataBytes} more than once
     * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
     * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
     * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
     * "stream can cannot be materialized more than once" exception.
     *
     * When called on `Strict` entities or sources whose values can be buffered in memory,
     * the above warnings can be ignored. Repeated materialization is not necessary in this case, avoiding
     * the mentioned exceptions due to the data being held in memory.
     *
     * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
     */
    DiscardedEntity discardEntityBytes(ClassicActorSystemProvider system);

    /**
     * Represents the currently being-drained HTTP Entity which triggers completion of the contained
     * Future once the entity has been drained for the given HttpMessage completely.
     */
    interface DiscardedEntity extends HttpEntity.DiscardedEntity {
    }

    interface MessageTransformations<Self> {
        /**
         * Returns a copy of this message with a new protocol.
         */
        Self withProtocol(HttpProtocol protocol);

        /**
         * Returns a copy of this message with the given header added to the list of headers.
         */
        Self addHeader(HttpHeader header);

        /**
         * Returns a copy of this message with the given headers added to the list of headers.
         */
        Self addHeaders(Iterable<HttpHeader> headers);

        /**
         * Returns a copy of this message with new headers.
         */
        Self withHeaders(Iterable<HttpHeader> headers);

        <T> Self addAttribute(AttributeKey<T> key, T value);

        /**
         * Returns a copy of this message with the given http credential header added to the list of headers.
         */
        Self addCredentials(HttpCredentials credentials);

        /**
         * Returns a copy of this message with all headers of the given name (case-insensitively) removed.
         */
        Self removeHeader(String headerName);

        /**
         * Returns a copy of this message with the attribute with this key (if any) removed.
         */
        Self removeAttribute(AttributeKey<?> key);

        /**
         * Returns a copy of this message with a new entity.
         */
        Self withEntity(String string);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(byte[] bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ByteString bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType.NonBinary type, String string);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, byte[] bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, ByteString bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, File file);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, Path file);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(RequestEntity entity);

        /**
         * Returns a copy of Self message after applying the given transformation
         */
        <T> Self transformEntityDataBytes(Graph<FlowShape<ByteString, ByteString>, T> transformer);

        /**
         * Returns a CompletionStage of Self message with strict entity that contains the same data as this entity
         * which is only completed when the complete entity has been collected. As the
         * duration of receiving the complete entity cannot be predicted, a timeout needs to
         * be specified to guard the process against running and keeping resources infinitely.
         *
         * Use getEntity().getDataBytes and stream processing instead if the expected data is big or
         * is likely to take a long time.
         */
        CompletionStage<? extends Self> toStrict(long timeoutMillis, Executor ec, Materializer materializer);

        /**
         * Returns a CompletionStage of Self message with strict entity that contains the same data as this entity
         * which is only completed when the complete entity has been collected. As the
         * duration of receiving the complete entity cannot be predicted, a timeout needs to
         * be specified to guard the process against running and keeping resources infinitely.
         *
         * Use getEntity().getDataBytes and stream processing instead if the expected data is big or
         * is likely to take a long time.
         */
        CompletionStage<? extends Self> toStrict(long timeoutMillis, long maxBytes, Executor ec, Materializer materializer);

        /**
         * Returns a CompletionStage of Self message with strict entity that contains the same data as this entity
         * which is only completed when the complete entity has been collected. As the
         * duration of receiving the complete entity cannot be predicted, a timeout needs to
         * be specified to guard the process against running and keeping resources infinitely.
         *
         * Use getEntity().getDataBytes and stream processing instead if the expected data is big or
         * is likely to take a long time.
         */
        CompletionStage<? extends Self> toStrict(long timeoutMillis, ClassicActorSystemProvider system);

        /**
         * Returns a CompletionStage of Self message with strict entity that contains the same data as this entity
         * which is only completed when the complete entity has been collected. As the
         * duration of receiving the complete entity cannot be predicted, a timeout needs to
         * be specified to guard the process against running and keeping resources infinitely.
         *
         * Use getEntity().getDataBytes and stream processing instead if the expected data is big or
         * is likely to take a long time.
         */
        CompletionStage<? extends Self> toStrict(long timeoutMillis, long maxBytes, ClassicActorSystemProvider system);

    }
}
