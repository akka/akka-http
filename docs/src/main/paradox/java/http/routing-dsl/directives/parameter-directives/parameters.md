<a id="parameters-java"></a>
# parameters

Extracts multiple *query* parameter values from the request.

If an unmarshaller throws an exception while extracting the value of a parameter, it will be handled as a rejection.
(see also @ref[Rejections](../../../routing-dsl/rejections.md))

## Description

See @ref[When to use which parameter directive?](index.md#which-parameter-directive-java) to understand when to use which directive.

## Example

@@snip [ParameterDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameters }
