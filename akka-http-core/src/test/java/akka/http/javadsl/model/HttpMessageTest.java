package akka.http.javadsl.model;

import akka.http.scaladsl.model.AttributeKey$;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import org.scalatestplus.junit.JUnitSuite;

public class HttpMessageTest extends JUnitSuite {
    @Test
    public void testRetrieveAttributeByKey() {
        AttributeKey<String> oneStringKey = AttributeKey.newInstance();
        AttributeKey<String> otherStringKey = AttributeKey.newInstance();
        AttributeKey<Integer> intKey = AttributeKey$.MODULE$.apply();

        String oneStringAttribute = "A string attribute!";
        String otherStringAttribute = "Another";
        Integer intAttribute = 42;

        HttpRequest request = HttpRequest.create()
                .addAttribute(oneStringKey, oneStringAttribute)
                .addAttribute(otherStringKey, otherStringAttribute)
                .addAttribute(intKey, intAttribute);

        assertEquals(oneStringAttribute, request.getAttribute(oneStringKey).get());
        assertEquals(otherStringAttribute, request.getAttribute(otherStringKey).get());
        assertEquals(intAttribute, request.getAttribute(intKey).get());
    }
}
