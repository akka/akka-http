/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util;

import scala.None$;
import scala.collection.immutable.Map$;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.OptionConverters;

import akka.stream.scaladsl.Source;
import akka.http.ccompat.MapHelpers;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Contains internal helper methods.
 */
public abstract class Util {
    @SuppressWarnings("unchecked") // no support for covariance of option in Java
    // needed to provide covariant conversions that the Java interfaces don't provide automatically.
    // The alternative would be having to cast around everywhere instead of doing it here in a central place.
    public static <U, T extends U> Optional<U> convertOption(scala.Option<T> o) {
        return (Optional<U>)(Object) OptionConverters.toJava(o);
    }
    @SuppressWarnings("unchecked") // no support for covariance of Publisher in Java
    // needed to provide covariant conversions that the Java interfaces don't provide automatically.
    // The alternative would be having to cast around everywhere instead of doing it here in a central place.
    public static <U, T extends U> Source<U, scala.Unit> convertPublisher(Source<T, scala.Unit> p) {
        return (Source<U, scala.Unit>)(Object) p;
    }
    @SuppressWarnings("unchecked")
    public static <T, U extends T> Source<U, scala.Unit> upcastSource(Source<T, scala.Unit> p) {
        return (Source<U, scala.Unit>)(Object) p;
    }
    public static scala.collection.immutable.Map<String, String> convertMapToScala(Map<String, String> map) {
        return MapHelpers.convertMapToScala(map);
    }
    @SuppressWarnings("unchecked") // contains an upcast
    public static <T, U extends T> scala.Option<U> convertOptionalToScala(Optional<T> o) {
        return OptionConverters.toScala((Optional<U>) o);
    }

    public static final scala.collection.immutable.Map<String, String> emptyMap =
        Map$.MODULE$.<String, String>empty();

    private static final scala.Option<?> noneValue = None$.MODULE$;
    @SuppressWarnings("unchecked")
    public static <T> scala.Option<T> scalaNone() {
        return (scala.Option<T>) noneValue;
    }

    @SuppressWarnings("unchecked")
    public static <T, U extends T> Seq<U> convertIterable(Iterable<T> els) {
        return scala.jdk.javaapi.CollectionConverters.asScala((Iterable<U>)els).toVector();
    }
    public static <T, U extends T> Seq<U> convertArray(T[] els) {
        return Util.<T, U>convertIterable(Arrays.asList(els));
    }

    public static <J, V extends J> Optional<J> lookupInRegistry(ObjectRegistry<Object, V> registry, int key) {
        return Util.<J, V>convertOption(registry.getForKey(key));
    }
    public static <J, V extends J> Optional<J> lookupInRegistry(ObjectRegistry<String, V> registry, String key) {
        return Util.<String, J, V>lookupInRegistry(registry, key);
    }
    public static <K, J, V extends J> Optional<J> lookupInRegistry(ObjectRegistry<K, V> registry, K key) {
        return Util.<J, V>convertOption(registry.getForKey(key));
    }

}
