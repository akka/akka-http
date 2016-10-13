<a id="formfieldmap"></a>
# formFieldMap

## Signature

@@signature [FormFieldDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala) { #formFieldMap }

## Description

Extracts all HTTP form fields at once as a `Map[String, String]` mapping form field names to form field values.

If form data contain a field value several times, the map will contain the last one.

See @ref[formFields](formFields.md#formfields) for an in-depth description.

## Warning

Use of this directive can result in performance degradation or even in `OutOfMemoryError` s.
See @ref[formFieldSeq](formFieldSeq.md#formfieldseq) for details.

## Example

@@snip [FormFieldDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala) { #formFieldMap }