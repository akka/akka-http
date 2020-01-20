/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.marshallers.jackson;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpEntity;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JacksonTest extends JUnitSuite {

  public static class SomeData {
    public final String field;
    @JsonCreator
    public SomeData(@JsonProperty("field") String field) {
      this.field = field;
    }
  }

  @Test
  public void failingToUnmarshallShouldProvideFailureDetails() throws Exception {
    ActorSystem sys = ActorSystem.create("test");
    try {
      Materializer materializer = ActorMaterializer.create(sys);
      CompletionStage<SomeData> unmarshalled = Jackson.unmarshaller(SomeData.class).unmarshal(HttpEntities.create(ContentTypes.APPLICATION_JSON, "{\"droids\":\"not the ones you are looking for\"}"), materializer);


        SomeData result = unmarshalled.toCompletableFuture().get(3, TimeUnit.SECONDS);
        throw new AssertionError("Invalid json should not parse to object");
    } catch (ExecutionException ex) {
      // CompletableFuture.get wraps in one layer of ExecutionException
      assertTrue(ex.getCause().getMessage().startsWith("Cannot unmarshal JSON as SomeData: Unrecognized field \"droids\""));
    } finally {
      sys.terminate();
    }
  }
}
