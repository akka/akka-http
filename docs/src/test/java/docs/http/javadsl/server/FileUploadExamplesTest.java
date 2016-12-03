/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.javadsl.FileIO;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class FileUploadExamplesTest extends JUnitRouteTest {

  private final Materializer materializer = materializer();

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  //#simple-upload
  Route uploadVideo() {
    return path("video", () ->
      entity(Unmarshaller.entityToMultipartFormData(), formData -> {
        // collect all parts of the multipart as it arrives into a map
        CompletionStage<Map<String, Object>> allParts =
          formData.getParts().mapAsync(1, bodyPart -> {
            if ("file".equals(bodyPart.getName())) {
              // stream into a file as the chunks of it arrives and return a CompletionStage
              // file to where it got stored
              final File file = File.createTempFile("upload", "tmp");
              return bodyPart.getEntity().getDataBytes()
                .runWith(FileIO.toPath(file.toPath()), materializer)
                .thenApply(ignore ->
                  new Pair<String, Object>(bodyPart.getName(), file)
                );
            } else {
              // collect form field values
              return bodyPart.toStrict(2 * 1000, materializer)
                .thenApply(strict ->
                  new Pair<String, Object>(bodyPart.getName(),
                    strict.getEntity().getData().utf8String())
                );
            }
          }).runFold(new HashMap<String, Object>(), (acc, pair) -> {
            acc.put(pair.first(), pair.second());
            return acc;
          }, materializer);

        // simulate a DB call
        CompletionStage<Void> done = allParts.thenCompose(map ->
          // You would have some better validation/unmarshalling here
          DB.create((File) map.get("file"),
            (String) map.get("title"),
            (String) map.get("author")
          ));

        // when processing have finished create a response for the user
        return onSuccess(() -> allParts, x -> complete("ok!"));
      })
    );

  }
  //#simple-upload


  static class DB {
    static CompletionStage<Void> create(final File file, final String title, final String author) {
      return CompletableFuture.completedFuture(null);
    }
  }
}
