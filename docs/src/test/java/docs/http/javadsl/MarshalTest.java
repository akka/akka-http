/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import static org.junit.Assert.assertEquals;
import akka.http.javadsl.testkit.JUnitRouteTest;

import akka.util.ByteString;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import org.junit.Test;

//#imports
import akka.http.javadsl.model.*;
import akka.http.javadsl.marshalling.Marshaller;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

//#imports

@SuppressWarnings("unused")
  public class MarshalTest extends JUnitRouteTest {

  @Test
  public void useMarshal() throws Exception {
    //#use-marshal
    /*
    CompletionStage<Integer> integerStage =
      StringUnmarshallers.INTEGER.unmarshal("42", system().dispatcher(), materializer());
    int integer = integerStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(integer, 42);

    CompletionStage<Boolean> boolStage =
      StringUnmarshallers.BOOLEAN.unmarshal("off", system().dispatcher(), materializer());
    boolean bool = boolStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(bool, false);


    String string = "Yeah"
    val entityFuture = Marshal(string).to[MessageEntity]
    val entity = Await.result(entityFuture, 1.second) // don't block in non-test code!
    entity.contentType shouldEqual ContentTypes.`text/plain(UTF-8)`

    val errorMsg = "Easy, pal!"
    val responseFuture = Marshal(420 -> errorMsg).to[HttpResponse]
    val response = Await.result(responseFuture, 1.second) // don't block in non-test code!
    response.status shouldEqual StatusCodes.EnhanceYourCalm
    response.entity.contentType shouldEqual ContentTypes.`text/plain(UTF-8)`

    val request = HttpRequest(headers = List(headers.Accept(MediaTypes.`application/json`)))
    val responseText = "Plaintext"
    val respFuture = Marshal(responseText).toResponseFor(request) // with content negotiation!
    a[Marshal.UnacceptableResponseContentTypeException] should be thrownBy {
      Await.result(respFuture, 1.second) // client requested JSON, we only have text/plain!
    }
    */

    //#use-marshal
  }
}
