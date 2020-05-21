/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Authorization;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpResponseDetailedStringExampleTest {

    // Custom string representation which includes headers
    public String toDetailedString(HttpResponse response) {

        StatusCode status = response.status();
        Iterable<HttpHeader> headers = response.getHeaders();
        HttpEntity entity = response.entity();
        HttpProtocol protocol = response.protocol();


        return String.format("HttpResponse(%s, %s, %s, %s)", status, headers, entity, protocol);
    }

    @Test
    public void headersInCustomStringRepresentation() {

        // An HTTP header containing Personal Identifying Information
        Authorization piiHeader = Authorization.basic("user", "password");

        // An HTTP entity containing Personal Identifying Information
        HttpEntity.Strict piiBody = HttpEntities.create("This body contains information about [user]");

        HttpResponse httpResponseWithHeadersAndBody = HttpResponse.create()
                .withEntity(piiBody)
                .withHeaders(Arrays.asList(piiHeader));

        // Our custom string representation includes body and headers string representations...
        assertTrue(toDetailedString(httpResponseWithHeadersAndBody).contains(piiHeader.toString()));
        assertTrue(toDetailedString(httpResponseWithHeadersAndBody).contains(piiBody.toString()));

        // ... while default `toString` doesn't.
        assertFalse(httpResponseWithHeadersAndBody.toString().contains(piiHeader.unsafeToString()));
        assertFalse(httpResponseWithHeadersAndBody.toString().contains(piiBody.getData().utf8String()));

    }

}
