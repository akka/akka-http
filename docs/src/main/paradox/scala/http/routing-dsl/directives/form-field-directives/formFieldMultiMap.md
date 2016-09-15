<a id="formfieldmultimap"></a>
# formFieldMultiMap

## Signature

FIXME@@snip [FormFieldDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala) { #formFieldMultiMap }

## Description

Extracts all HTTP form fields at once as a multi-map of type `Map[String, List[String]` mapping
a form name to a list of all its values.

This directive can be used if form fields can occur several times.

The order of values is *not* specified.

See @ref[formFields](formFields.md#formfields) for an in-depth description.

## Warning

Use of this directive can result in performance degradation or even in `OutOfMemoryError` s.
See @ref[formFieldSeq](formFieldSeq.md#formfieldseq) for details.

## Example

@@snip [FormFieldDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala) { #formFieldMultiMap }