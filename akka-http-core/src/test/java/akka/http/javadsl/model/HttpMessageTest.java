/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.scaladsl.model.AttributeKey$;
import akka.stream.scaladsl.TLSPlacebo;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HttpMessageTest extends JUnitSuite {
    @Test
    public void testRetrieveAttributeByKey() {
        AttributeKey<String> oneStringKey = AttributeKey.create("one", String.class);
        // keys with the same type but different names should be considered different
        AttributeKey<String> otherStringKey = AttributeKey.create("other", String.class);

        // it should be possible to use 'Scala attribute keys' in the Java API's
        AttributeKey<Integer> intKey = AttributeKey$.MODULE$.apply("int", Integer.class);
        // keys with the same name but different types should be considered different
        AttributeKey<Integer> otherIntKey = AttributeKey.create("other", Integer.class);

        String oneString = "A string attribute!";
        String otherString = "Another";
        Integer integer = 42;
        Integer otherInteger = 37;

        HttpRequest request = HttpRequest.create()
                .addAttribute(oneStringKey, oneString)
                .addAttribute(otherStringKey, otherString)
                .addAttribute(intKey, integer)
                .addAttribute(otherIntKey, otherInteger);

        assertEquals(oneString, request.getAttribute(oneStringKey).get());
        assertEquals(otherString, request.getAttribute(otherStringKey).get());
        assertEquals(integer, request.getAttribute(intKey).get());
        assertEquals(otherInteger, request.getAttribute(otherIntKey).get());

        HttpRequest smaller = request.removeAttribute(intKey);
        assertEquals(otherString, smaller.getAttribute(otherStringKey).get());
        assertFalse(smaller.getAttribute(intKey).isPresent());
    }

    @Test
    public void testAttributeKeys() {
        HttpRequest request = HttpRequest.create();
        RemoteAddress remoteAddress = RemoteAddress.create(new byte[]{10,0,0,1});
        HttpRequest newRequest = request.addAttribute(AttributeKeys.remoteAddress, remoteAddress);

        assert(newRequest.getAttribute(AttributeKeys.remoteAddress).get().equals(remoteAddress));
    }

    @Test
    public void testSslAttribute() throws Exception {
        SSLSession session = SSLContext.getDefault().createSSLEngine().getSession();
        HttpRequest request = HttpRequest.create()
          .addAttribute(AttributeKeys.sslSession, SslSessionInfo.create(session));

        assert(request.getAttribute(AttributeKeys.sslSession).get().getSession() == session);
    }
}
