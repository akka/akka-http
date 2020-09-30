# Client-Side HTTPS Support

Akka HTTP supports TLS encryption on the client-side as well as on the @ref[server-side](../server-side/server-https-support.md).

The central vehicle for configuring encryption is the @apidoc[HttpsConnectionContext], which can be created using
the static methods on @apidoc[ConnectionContext]:

Scala
:  @@snip[ConnectionContext.scala](/akka-http-core/src/main/scala/akka/http/scaladsl/ConnectionContext.scala) { #https-client-context-creation }

Java
:  @@snip [ConnectionContext.scala](/akka-http-core/src/main/scala/akka/http/javadsl/ConnectionContext.scala) { #https-client-context-creation }

In addition to the `outgoingConnection`, `newHostConnectionPool` and `cachedHostConnectionPool` methods the
@scala[@scaladoc[akka.http.scaladsl.Http](akka.http.scaladsl.Http$)]@java[@javadoc[akka.http.javadsl.Http](akka.http.javadsl.Http)]
extension also defines `outgoingConnectionHttps`, `newHostConnectionPoolHttps` and
`cachedHostConnectionPoolHttps`. These methods work identically to their counterparts without the `-Https` suffix,
with the exception that all connections will always be encrypted.

The `singleRequest` and `superPool` methods determine the encryption state via the scheme of the incoming request,
i.e. requests to an "https" URI will be encrypted, while requests to an "http" URI won't.

The encryption configuration for all HTTPS connections, i.e. the `HttpsContext` is determined according to the
following logic:

 1. If the optional `httpsContext` method parameter is defined it contains the configuration to be used (and thus
takes precedence over any potentially set default client-side `HttpsContext`).
 2. If the optional `httpsContext` method parameter is undefined (which is the default) the default client-side
`HttpsContext` is used, which can be set via the `setDefaultClientHttpsContext` on the @apidoc[Http$] extension.
 3. If no default client-side `HttpsContext` has been set via the `setDefaultClientHttpsContext` on the @apidoc[Http$]
extension the default system configuration is used.

Usually the process is, if the default system TLS configuration is not good enough for your application's needs,
that you configure a custom `HttpsContext` instance and set it via
@scala[`Http().setDefaultClientHttpsContext`]@java[`Http.get(system).setDefaultClientHttpsContext`].
Afterwards you simply use `outgoingConnectionHttps`, `newHostConnectionPoolHttps`, `cachedHostConnectionPoolHttps`,
`superPool` or `singleRequest` without a specific `httpsContext` argument, which causes encrypted connections
to rely on the configured default client-side @apidoc[HttpsConnectionContext].

If no custom `HttpsContext` is defined the default context uses Java's default TLS settings. Customizing the
`HttpsContext` can make the Https client less secure. Understand what you are doing!

## Detailed configuration and workarounds

@@@ warning

While it is possible to disable certain checks, we **strongly recommend**
to instead attempt to solve these issues by properly configuring TLS–for example by adding trusted keys to the keystore.

If however certain checks really need to be disabled because of misconfigured (or legacy) servers that your
application has to speak to, instead of disabling the checks globally (by using `setDefaultClientHttpsContext`) we suggest
configuring the loose settings for *specific connections* that are known to need them disabled (and trusted for some other reason).
The pattern of doing so is documented in the following sub-sections.

@@@

### Disabling hostname verification

Hostname verification proves that the Akka HTTP client is actually communicating with the server it intended to
communicate with. Without this check a man-in-the-middle attack is possible. In the attack scenario, an alternative
certificate would be presented which was issued for another host name. Checking the host name in the certificate
against the host name the connection was opened against is therefore vital.

When you create your @apidoc[HttpsConnectionContext] with @apidoc[ConnectionContext.httpsClient](ConnectionContext) enables hostname verification. The following shows an example of disabling hostname verification for a given connection:

Scala
:  @@snip [HttpsExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpsExamplesSpec.scala) { #disable-hostname-verification-connection }

Java
:  @@snip [HttpsExamplesDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpsExamplesDocTest.java) { #disable-hostname-verification-connection }
