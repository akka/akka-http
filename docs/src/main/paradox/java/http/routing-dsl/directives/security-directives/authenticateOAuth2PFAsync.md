<a id="authenticateoauth2pfasync-java"></a>
# authenticateOAuth2PFAsync

Wraps the inner route with OAuth Bearer Token authentication support using a given `AsyncAuthenticatorPF<T>`.

## Description

Provides support for extracting the so-called "*Bearer Token*" from the `Authorization` HTTP Header,
which is used to initiate an OAuth2 authorization.

> **Warning:**
This directive does not implement the complete OAuth2 protocol, but instead enables implementing it,
by extracting the needed token from the HTTP headers.

Refer to @ref[authenticateOAuth2-java](authenticateOAuth2.md#authenticateoauth2-java) for a detailed description of this directive.

Its semantics are equivalent to `authenticateOAuth2PF` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a `AuthenticationFailedRejection` rejection.

See also @ref[authenticateOAuth2PF-java](authenticateOAuth2PF.md#authenticateoauth2pf-java) if the authorization operation is rather quick, and does not have to execute asynchronously.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-java) for details about verifying the secret.

For more information on how OAuth2 works see [RFC 6750](https://tools.ietf.org/html/rfc6750).

## Example

Usage in code is exactly the same as @ref[authenticateBasicPFAsync-java](authenticateBasicPFAsync.md#authenticatebasicpfasync-java),
with the difference that one must validate the token as OAuth2 dictates (which is currently not part of Akka HTTP itself).