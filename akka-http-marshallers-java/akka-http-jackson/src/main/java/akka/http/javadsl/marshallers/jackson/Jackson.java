/*
 * Copyright (C) 2009-2017 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.marshallers.jackson;

import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.RequestEntity;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.util.ByteString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;

public class Jackson {
  private static final ObjectMapper defaultObjectMapper = new ObjectMapper()
    .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
    .registerModule(new Jdk8Module())
    .setSerializationInclusion(JsonInclude.Include.NON_ABSENT);

  public static <T> Marshaller<T, RequestEntity> marshaller() {
    return marshaller(defaultObjectMapper);
  }

  public static <T> Marshaller<T, RequestEntity> marshaller(ObjectMapper mapper) {
    return Marshaller.wrapEntity(
      u -> toJSON(mapper, u),
      Marshaller.stringToEntity(),
      MediaTypes.APPLICATION_JSON
    );
  }

  public static <T> Unmarshaller<ByteString, T> byteStringUnmarshaller(Class<T> expectedType) {
    return byteStringUnmarshaller(defaultObjectMapper, expectedType);
  }

  public static <T> Unmarshaller<HttpEntity, T> unmarshaller(Class<T> expectedType) {
    return unmarshaller(defaultObjectMapper, expectedType);
  }

  public static <T> Unmarshaller<HttpEntity, T> unmarshaller(ObjectMapper mapper, Class<T> expectedType) {
    return Unmarshaller
      .forMediaType(MediaTypes.APPLICATION_JSON, Unmarshaller.entityToString())
      .thenApply(s -> fromJSON(mapper, s, expectedType));
    }

  public static <T> Unmarshaller<ByteString, T> byteStringUnmarshaller(ObjectMapper mapper, Class<T> expectedType) {
    return Unmarshaller.sync(s -> fromJSON(mapper, s.utf8String(), expectedType));
  }

  private static String toJSON(ObjectMapper mapper, Object object) {
    try {
      if (object == null) return "";

      final String candidate = mapper.writeValueAsString(object);
      final Class<?> type = object.getClass();

      if (type.isAssignableFrom(String.class)) return handleRootStrings(candidate);
      else return handleRootNull(candidate);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot marshal to JSON: " + object, e);
    }
  }

  private static <T> T fromJSON(ObjectMapper mapper, String json, Class<T> expectedType) {
    try {
      return mapper.readerFor(expectedType).readValue(json);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot unmarshal JSON as " + expectedType.getSimpleName(), e);
    }
  }

  private static String handleRootNull(String candidate) {
    if ("null".equals(candidate)) return "";
    else return candidate;
  }

  private static String handleRootStrings(String candidate) {
    return candidate.substring(1, candidate.length()-1);
  }
}
