/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Authorization;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;

public class HttpRequestDetailedStringExampleTest {

    // Custom string representation which includes headers
    public String toDetailedString(HttpRequest request) {

        HttpMethod method = request.method();
        Uri uri = request.getUri();
        Iterable<HttpHeader> headers = request.getHeaders();
        RequestEntity entity = request.entity();
        HttpProtocol protocol = request.protocol();

        return String.format("HttpRequest(%s, %s, %s, %s, %s)", method, uri, headers, entity, protocol);
    }

    @Test
    public void headersInCustomStringRepresentation() {

        // An HTTP header containing Personal Identifying Information
        Authorization piiHeader = Authorization.basic("user", "password");

        // An HTTP entity containing Personal Identifying Information
        HttpEntity.Strict piiBody = HttpEntities.create("This body contains information about [user]");

        HttpRequest httpRequestWithHeadersAndBody = HttpRequest.create()
                .withEntity(piiBody)
                .withHeaders(Arrays.asList(piiHeader));

        // Our custom string representation includes body and headers string representations...
        assertTrue(toDetailedString(httpRequestWithHeadersAndBody).contains(piiHeader.toString()));
        assertTrue(toDetailedString(httpRequestWithHeadersAndBody).contains(piiBody.toString()));

        // ... while default `toString` doesn't.
        assertFalse(httpRequestWithHeadersAndBody.toString().contains(piiHeader.unsafeToString()));
        assertFalse(httpRequestWithHeadersAndBody.toString().contains(piiBody.getData().utf8String()));

    }

}
