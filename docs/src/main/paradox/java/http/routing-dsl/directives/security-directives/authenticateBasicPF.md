<a id="authenticatebasicpf-java"></a>
# authenticateBasicPF

Wraps the inner route with Http Basic authentication support using a given `AuthenticatorPF<T>`.

## Description

Provides support for handling [HTTP Basic Authentication](https://en.wikipedia.org/wiki/Basic_auth).

Refer to @ref[authenticateBasic-java](authenticateBasic.md#authenticatebasic-java) for a detailed description of this directive.

Its semantics are equivalent to `authenticateBasicPF` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a `AuthenticationFailedRejection` rejection.

Longer-running authentication tasks (like looking up credentials in a database) should use @ref[authenticateBasicAsync-java](authenticateBasicAsync.md#authenticatebasicasync-java)
or @ref[authenticateBasicPFAsync-java](authenticateBasicPFAsync.md#authenticatebasicpfasync-java) if you prefer to use the `PartialFunction` syntax.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-java) for details about verifying the secret.

> **Warning:**
Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

## Example

@@snip [SecurityDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateBasicPF }