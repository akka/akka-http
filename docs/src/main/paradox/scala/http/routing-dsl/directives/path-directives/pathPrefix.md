<a id="pathprefix"></a>
# pathPrefix

## Signature

FIXME@@snip [PathDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathPrefix }

## Description

Matches and consumes a prefix of the unmatched path of the `RequestContext` against the given `PathMatcher`,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing `pathPrefix` or @ref[rawPathPrefix](rawPathPrefix.md#rawpathprefix) directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a `PathMatcher` instance (see also: @ref[The PathMatcher DSL](../../path-matchers.md#pathmatcher-dsl)).

As opposed to its @ref[rawPathPrefix](rawPathPrefix.md#rawpathprefix) counterpart `pathPrefix` automatically adds a leading slash to its
`PathMatcher` argument, you therefore don't have to start your matching expression with an explicit slash.

Depending on the type of its `PathMatcher` argument the `pathPrefix` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

> **Note:**
The empty string (also called empty word or identity) is a **neutral element** of string concatenation operation,
so it will match everything and consume nothing. The @ref[path](path.md#path) provides more strict behaviour.

## Example

@@snip [PathDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #pathPrefix- }