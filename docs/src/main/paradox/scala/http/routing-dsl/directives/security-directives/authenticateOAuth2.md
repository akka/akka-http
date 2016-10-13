<a id="authenticateoauth2"></a>
# authenticateOAuth2

## Signature

@@signature [SecurityDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #Authenticator }

@@signature [SecurityDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #authenticateOAuth2 }

## Description

Wraps the inner route with OAuth Bearer Token authentication support using a given `AuthenticatorPF[T]`

Provides support for extracting the so-called "*Bearer Token*" from the `Authorization` HTTP Header,
which is used to initiate an OAuth2 authorization.

> **Warning:**
This directive does not implement the complete OAuth2 protocol, but instead enables implementing it,
by extracting the needed token from the HTTP headers.

Given a function returning `Some[T]` upon successful authentication and `None` otherwise,
respectively applies the inner route or rejects the request with a `AuthenticationFailedRejection` rejection,
which by default is mapped to an `401 Unauthorized` response.

Longer-running authentication tasks (like looking up credentials in a database) should use the @ref[authenticateOAuth2Async](authenticateOAuth2Async.md#authenticateoauth2async)
variant of this directive which allows it to run without blocking routing layer of Akka HTTP, freeing it for other requests.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-scala) for details about verifying the secret.

For more information on how OAuth2 works see [RFC 6750](https://tools.ietf.org/html/rfc6750).

## Example

Usage in code is exactly the same as @ref[authenticateBasic](authenticateBasic.md#authenticatebasic),
with the difference that one must validate the token as OAuth2 dictates (which is currently not part of Akka HTTP itself).