package docs.http.javadsl;

import java.util.concurrent.CompletionStage;
import akka.japi.Function;
import akka.actor.ExtendedActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.Materializer;

//#bindAndHandleAsync
import akka.http.javadsl.Http;
import akka.http.javadsl.ConnectHttp;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

//#bindAndHandleAsync

class Http2Test {
  void testBindAndHandleAsync() {
    Function<HttpRequest, CompletionStage<HttpResponse>> asyncHandler = null;
    Materializer materializer = null;
    ExtendedActorSystem system = null;

    //#bindAndHandleAsync
    Http http = new Http(system);

    // TODO this doesn't actually appear to work? Will try with http.setDefaultServerHttpContext
    http.bindAndHandleAsync(asyncHandler, toHostHttps("127.0.0.1", 8443), materializer);
    //#bindAndHandleAsync
  }
}