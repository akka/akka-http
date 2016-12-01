/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.Multipart;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.javadsl.FileIO;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public class FileUploadExamplesTest extends JUnitRouteTest {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  //#simple-upload
  Route uploadVideo() {

    return path("video", () ->
      entity(Jackson.unmarshaller(Multipart.FormData.class), formData -> {
        // collect all parts of the multipart as it arrives into a map
        CompletionStage<Map<String, Object>> allParts = formData.getParts().mapAsync(1, bodyPart -> {
          if ("file".equals(bodyPart.getName())) {
            // stream into a file as the chunks of it arrives and return a CompletionStage
            // file to where it got stored
            final File file = File.createTempFile("upload", "tmp");
            return bodyPart.getEntity().getDataBytes()
              .runWith(FileIO.toPath(file.toPath()), materializer())
              .thenApply(foo -> new HashMap<String, Object>().put(bodyPart.getName(), file));
          } else {
            // collect form field values
            return bodyPart.toStrict(2 * 1000, materializer())
              .thenApply(strict ->
                new HashMap<String, Object>().put(bodyPart.getName(), strict.getEntity().getData().utf8String())
              );
          }
        }).runFold(new HashMap<String, Object>(), (acc, map) -> {
          acc.putAll((HashMap<String, Object>) map);
          return acc;
        }, materializer());

        // TODO "save" in "DB"

        // when processing have finished create a response for the user
        return onSuccess(() -> allParts, x -> complete("ok!"));
      })
    );

  }
  //#simple-upload
}
