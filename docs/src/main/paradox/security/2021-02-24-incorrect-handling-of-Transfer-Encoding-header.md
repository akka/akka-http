# Incorrect Handling Of Transfer-Encoding Header

## Date

24 February 2021

## Description of Vulnerability

HTTP/1.1 defines rules which `Transfer-Encoding` headers are valid and how they should be interpreted. In particular, a `Transfer-Encoding: chunked` header and a `Content-Length` header
are not allowed to appear in a single message at the same time. This is important to unambiguously delimit subsequent HTTP messages on a connection.

In theory, HTTP/1.1 allows multiple encodings, although, in practice, only `chunked` is relevant. In the case that multiple encodings are present,
vulnerable versions of Akka HTTP do not correctly validate the rules of the specification and effectively ignore the `Transfer-Encoding` header, use
a `Content-Length` header if present for delimiting a message, and pass the message to the user unchanged.

If users used Akka HTTP as a reverse proxy, such a message might be forwarded to a backend server. This can potentially lead to "Request Smuggling" if the backend server has a similar but
different interpretation for that (invalid) set of headers.

## Severity

Based on our assessment, the CVSS score of this vulnerability is 4.2 (Medium), based on vector [(AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:N/E:U/RL:O/RC:C)](https://nvd.nist.gov/vuln-metrics/cvss/v3-calculator?vector=AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:N/E:U/RL:O/RC:C&version=3.1).

## Impact

A vulnerable Akka HTTP server will accept a malformed message as described above and hand it over to the user. If the user application proxies this message to another server unchanged
and that server also accepts that message but interprets it as two HTTP messages, the second message has reached the second server without having been inspected by the proxy.

Note that Akka HTTP itself does currently not provide functionality to proxy requests to other servers (but it's easy to build).

In summary, these conditions must be true for an application to be vulnerable:

 * use a vulnerable version of Akka HTTP
 * the application must proxy requests to a backend server
 * the backend server must have another bug that accepts the message and interprets the malformed message as two messages

## Resolution

Akka HTTP will no longer accept multiple encodings in `Transfer-Encoding` but only a single `chunked` encoding is valid. HTTP message carrying a combination of `Transfer-Encoding` and
`Content-Length` headers are rejected.

## Affected versions

- akka-http prior to `10.2.4` and  `10.1.14`

## Fixed versions

- akka-http `10.2.4`
- akka-http `10.1.14`

## Acknowledgements

Thanks, Bastian Ike and Sebastian Rose of AOE for bringing this issue to our attention.

## References

 * [CVE-2021-23339](https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2021-23339)
 * [SNYK-JAVA-COMTYPESAFEAKKA-1075043](https://snyk.io/vuln/SNYK-JAVA-COMTYPESAFEAKKA-1075043)
 * [#3754](https://github.com/akka/akka-http/pull/3754)