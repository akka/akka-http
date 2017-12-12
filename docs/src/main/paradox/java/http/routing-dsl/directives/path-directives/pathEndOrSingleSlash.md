# pathEndOrSingleSlash

## Description

Only passes the request to its inner route if the unmatched path of the @unidoc[RequestContext] is either empty
or contains only one single slash.

This directive is a simple alias for `rawPathPrefix(Slash.? ~ PathEnd)` and is mostly used on an inner-level to
discriminate "path already fully matched" from other alternatives (see the example below). For a comparison between path directives check @ref[Overview of path directives](index.md#overview-path-java).

It is equivalent to `pathEnd | pathSingleSlash` but slightly more efficient.

## Example

@@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-end-or-single-slash }