/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.scaladsl.model.IllegalUriException;
import akka.japi.Pair;
import org.scalatest.junit.JUnitSuite;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static akka.http.javadsl.model.Uri.RELAXED;
import static akka.http.javadsl.model.Uri.STRICT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class UriTest extends JUnitSuite {

  @Test
  public void testValidUrlExamples() {
    //#valid-uri-examples
    Uri uri1 = Uri.create("ftp://ftp.is.co.za/rfc/rfc1808.txt");
    assertEquals("ftp", uri1.getScheme());
    assertEquals(Host.create("ftp.is.co.za"), uri1.getHost());
    assertEquals("/rfc/rfc1808.txt", uri1.getPathString());

    Uri uri2 = Uri.create("http://www.ietf.org/rfc/rfc2396.txt");
    assertEquals("http", uri2.getScheme());
    assertEquals(Host.create("www.ietf.org"), uri2.getHost());
    assertEquals("/rfc/rfc2396.txt", uri2.getPathString());

    Uri uri3 = Uri.create("ldap://[2001:db8::7]/c=GB?objectClass?one");
    assertEquals("ldap", uri3.getScheme());
    assertEquals(Host.create("[2001:db8::7]"), uri3.getHost());
    assertEquals("objectClass?one", uri3.query().toString());

    Uri uri4 = Uri.create("mailto:John.Doe@example.com");
    assertEquals("mailto", uri4.getScheme());
    assertEquals("John.Doe@example.com", uri4.getPathString());

    Uri uri5 = Uri.create("news:comp.infosystems.www.servers.unix");
    assertEquals("news", uri5.getScheme());
    assertEquals("comp.infosystems.www.servers.unix", uri5.getPathString());

    Uri uri6 = Uri.create("tel:+1-816-555-1212");
    assertEquals("tel", uri6.getScheme());
    assertEquals("+1-816-555-1212", uri6.getPathString());

    Uri uri7 = Uri.create("telnet://192.0.2.16:80/");
    assertEquals("telnet", uri7.getScheme());
    assertEquals(Host.create("192.0.2.16"), uri7.getHost());
    assertEquals("/", uri7.getPathString());

    Uri uri8 = Uri.create("urn:oasis:names:specification:docbook:dtd:xml:4.1.2");
    assertEquals("urn", uri8.getScheme());
    assertEquals("oasis:names:specification:docbook:dtd:xml:4.1.2", uri8.getPathString());
    //#valid-uri-examples
  }

  @Test
  public void testPercentEscape() {
    //#dont-double-decode
    Uri uri1 = Uri.create("http://foo.com?foo=%2520");
    assertEquals(Optional.of("%20"), uri1.query().get("foo"));
    Uri uri2 = Uri.create("http://foo.com?foo=%2F%5C");
    assertEquals(Optional.of("/\\"), uri2.query().get("foo"));
    //#dont-double-decode
  }

  //#illegal-scheme
  @Test
  public void testIllegalScheme() {
    Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("foö:/a"));
    //IllegalUriException(
    //  "Illegal URI reference: Invalid input 'ö', expected scheme-char, 'EOI', '#', ':', '?', slashSegments or pchar (line 1, column 3)",
    //  "http://user:ö@host\n" +
    //  "            ^"
    //)
  }
  //#illegal-scheme

  //#illegal-userinfo
  @Test
  public void testIllegalUserInfo() {
      Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("http://user:ö@host"));
    //IllegalUriException(
    //  "Illegal URI reference: Invalid input 'ö', expected userinfo-char, pct-encoded, '@' or port (line 1, column 13)",
    //  "http://use%2G@host\n" +
    //  "            ^"
    //)
  }
  //#illegal-userinfo

  //#illegal-percent-encoding
  @Test
  public void testIllegalPercentEncoding() {
      Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("http://use%2G@host"));
    //IllegalUriException(
    //  "Illegal URI reference: Invalid input 'G', expected HEXDIG (line 1, column 13)",
    //  "http://www.example.com/name with spaces/\n" +
    //  "                           ^"
    //)
  }
  //#illegal-percent-encoding

  //#illegal-path
  @Test
  public void testIllegalPath() {
      Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("http://www.example.com/name with spaces/"));
    //IllegalUriException(
    //  "Illegal URI reference: Invalid input ' ', expected '/', 'EOI', '#', '?' or pchar (line 1, column 28)",
    //  "http://www.example.com/name with spaces/\n" +
    //  "                           ^"
    //)
  }
  //#illegal-path

  //#illegal-path-with-control-char
  @Test
  public void testIllegalPathWithControlCharacter() {
      Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("http:///with\newline"));
    //IllegalUriException(
    //  "Illegal URI reference: Invalid input '\\n', expected '/', 'EOI', '#', '?' or pchar (line 1, column 13)",
    //  "http:///with\n" +
    //  "            ^"
    //)
  }
  //#illegal-path-with-control-char

  @Test
  public void testIllegalQuery() {
    //#illegal-query
      Assertions.assertThrows(IllegalUriException.class, () -> Uri.create("?a%b=c").query());
    //IllegalUriException(
    //  " Illegal query: Invalid input '=', expected HEXDIG (line 1, column 4): a%b=c",
    //  "a%b=c\n" +
    //  " ^"
    //)
    //#illegal-query
  }

  //#query-strict-definition
  public Query strict(String query){
    return Query.create(query, akka.http.javadsl.model.Uri.STRICT);
  }
  //#query-strict-definition


  @Test
  public void testStrictMode() {
    //#query-strict-mode
    //query component (name: "a", and value: "b") is equal to parsed query string "a=b"
    assertEquals(Query.create(Pair.create("a", "b")), strict("a=b"));

    assertEquals(Query.create(Pair.create("", "")), strict(""));
    assertEquals(Query.create(Pair.create("a", "")), strict("a"));
    assertEquals(Query.create(Pair.create("a", "")), strict("a="));
    assertEquals(Query.create(Pair.create("a", " ")), strict("a=+"));
    assertEquals(Query.create(Pair.create("a", "+")), strict("a=%2B"));
    assertEquals(Query.create(Pair.create("", "a")), strict("=a"));
    assertEquals(Query.create(Pair.create("a", "")).withParam("", ""), strict("a&"));
    assertEquals(Query.create(Pair.create("a", "b")), strict("a=%62"));

    assertEquals(Query.create(Pair.create("a=b", "c")), strict("a%3Db=c"));
    assertEquals(Query.create(Pair.create("a&b", "c")), strict("a%26b=c"));
    assertEquals(Query.create(Pair.create("a+b", "c")), strict("a%2Bb=c"));
    assertEquals(Query.create(Pair.create("a;b", "c")), strict("a%3Bb=c"));

    assertEquals(Query.create(Pair.create("a", "b=c")), strict("a=b%3Dc"));
    assertEquals(Query.create(Pair.create("a", "b&c")), strict("a=b%26c"));
    assertEquals(Query.create(Pair.create("a", "b+c")), strict("a=b%2Bc"));
    assertEquals(Query.create(Pair.create("a", "b;c")), strict("a=b%3Bc"));

    assertEquals(Query.create(Pair.create("a b", "c")), strict("a+b=c")); //'+' is parsed to ' '
    assertEquals(Query.create(Pair.create("a", "b c")), strict("a=b+c")); //'+' is parsed to ' '
    //#query-strict-mode

    //#query-strict-without-percent-encode
    assertEquals(Query.create(Pair.create("a?b", "c")), strict("a?b=c"));
    assertEquals(Query.create(Pair.create("a/b", "c")), strict("a/b=c"));

    assertEquals(Query.create(Pair.create("a", "b?c")), strict("a=b?c"));
    assertEquals(Query.create(Pair.create("a", "b/c")), strict("a=b/c"));
    //#query-strict-without-percent-encode
  }

  //#query-strict-mode-exception-1
  @Test
  public void testStrictModeException1() {
      Assertions.assertThrows(IllegalUriException.class, () -> strict("a^=b"));
    //IllegalUriException(
    //  "Illegal query: Invalid input '^', expected '+', '=', query-char, 'EOI', '&' or pct-encoded (line 1, column 2)",
    //  "a^=b\n" +
    //  " ^")
  }
  //#query-strict-mode-exception-1

  //#query-strict-mode-exception-2
  @Test
  public void testStrictModeException2() {
      Assertions.assertThrows(IllegalUriException.class, () -> strict("a;=b"));
    //IllegalUriException(
    //  "Illegal query: Invalid input ';', expected '+', '=', query-char, 'EOI', '&' or pct-encoded (line 1, column 2)",
    //  "a;=b\n" +
    //  " ^")
  }
  //#query-strict-mode-exception-2

  //#query-strict-mode-exception-3
  @Test
  public void testStrictModeException3() {
    // double '=' in query string is invalid
      Assertions.assertThrows(IllegalUriException.class, () -> strict("a=b=c"));
    //IllegalUriException(
    //  "Illegal query: Invalid input '=', expected '+', query-char, 'EOI', '&' or pct-encoded (line 1, column 4)",
    //  "a=b=c\n"  +
    //  " ^")
  }
  //#query-strict-mode-exception-3

  //#query-strict-mode-exception-4
  @Test()
  public void testStrictModeException4() {
    // following '%', it should be percent encoding (HEXDIG), but "%b=" is not a valid percent encoding
    Assertions.assertThrows(IllegalUriException.class, () -> strict("a%b=c"));

    //IllegalUriException(
    //  "Illegal query: Invalid input '=', expected HEXDIG (line 1, column 4)",
    //  "a%b=c\n" +
    //  "   ^")
  }
  //#query-strict-mode-exception-4

  //#query-relaxed-definition
  public Query relaxed(String query){
    return Query.create(query,  akka.http.javadsl.model.Uri.RELAXED);
  }
  //#query-relaxed-definition

  @Test
  public void testRelaxedMode() {
    //#query-relaxed-mode
    assertEquals(Query.create(Pair.create("", "")), relaxed(""));
    assertEquals(Query.create(Pair.create("a", "")), relaxed("a"));
    assertEquals(Query.create(Pair.create("a", "")), relaxed("a="));
    assertEquals(Query.create(Pair.create("a", " ")), relaxed("a=+"));
    assertEquals(Query.create(Pair.create("a", "+")), relaxed("a=%2B"));
    assertEquals(Query.create(Pair.create("", "a")), relaxed("=a"));
    assertEquals(Query.create(Pair.create("a", "")).withParam("", ""), relaxed("a&"));
    assertEquals(Query.create(Pair.create("a", "b")), relaxed("a=%62"));

    assertEquals(Query.create(Pair.create("a=b", "c")), relaxed("a%3Db=c"));
    assertEquals(Query.create(Pair.create("a&b", "c")), relaxed("a%26b=c"));
    assertEquals(Query.create(Pair.create("a+b", "c")), relaxed("a%2Bb=c"));
    assertEquals(Query.create(Pair.create("a;b", "c")), relaxed("a%3Bb=c"));

    assertEquals(Query.create(Pair.create("a", "b=c")), relaxed("a=b%3Dc"));
    assertEquals(Query.create(Pair.create("a", "b&c")), relaxed("a=b%26c"));
    assertEquals(Query.create(Pair.create("a", "b+c")), relaxed("a=b%2Bc"));
    assertEquals(Query.create(Pair.create("a", "b;c")), relaxed("a=b%3Bc"));

    assertEquals(Query.create(Pair.create("a b", "c")), relaxed("a+b=c")); //'+' is parsed to ' '
    assertEquals(Query.create(Pair.create("a", "b c")), relaxed("a=b+c")); //'+' is parsed to ' '
    //#query-relaxed-mode

    //#query-relaxed-without-percent-encode
    assertEquals(Query.create(Pair.create("a?b", "c")), relaxed("a?b=c"));
    assertEquals(Query.create(Pair.create("a/b", "c")), relaxed("a/b=c"));

    assertEquals(Query.create(Pair.create("a", "b?c")), relaxed("a=b?c"));
    assertEquals(Query.create(Pair.create("a", "b/c")), relaxed("a=b/c"));
    //#query-relaxed-without-percent-encode

    //#query-relaxed-mode-success
    assertEquals(Query.create(Pair.create("a^", "b")), relaxed("a^=b"));
    assertEquals(Query.create(Pair.create("a;", "b")), relaxed("a;=b"));
    assertEquals(Query.create(Pair.create("a", "b=c")), relaxed("a=b=c"));
    //#query-relaxed-mode-success
  }

  //#query-relaxed-mode-exception-1
  @Test
  public void testRelaxedModeException1() {
    //following '%', it should be percent encoding (HEXDIG), but "%b=" is not a valid percent encoding
    //still invalid even in relaxed mode
      Assertions.assertThrows(IllegalUriException.class, () -> relaxed("a%b=c"));
    //IllegalUriException(
    //  "Illegal query: Invalid input '=', expected '+', query-char, 'EOI', '&' or pct-encoded (line 1, column 4)",
    //  "a%b=c\n" +
    //  "   ^")
  }
  //#query-relaxed-mode-exception-1

}
