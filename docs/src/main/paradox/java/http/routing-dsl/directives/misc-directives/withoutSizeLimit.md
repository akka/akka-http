<a id="withoutsizelimit-java"></a>
# withoutSizeLimit

## Description

Skips request entity size verification.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have `akka.http.parsing.max-content-length` relatively low and use `withoutSizeLimit`
directive just for endpoints for which size verification should not be performed.

See also @ref[withSizeLimit-java](withSizeLimit.md#withsizelimit-java) for setting request entity size limit.

## Example

@@snip [MiscDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #withSizeLimitExample }