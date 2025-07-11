# XML Support

Akka HTTP's @ref[marshalling](marshalling.md) and @ref[unmarshalling](unmarshalling.md)
infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON,
XML or even binary encodings.

@@@ div { .group-java }

Akka HTTP does not currently provide a Java API for XML support. If you need to
produce and consume XML, you can write a @ref[custom marshaller](marshalling.md#custom-marshallers)
using [Jackson], which is also the library used for providing @ref[JSON support](json-support.md#jackson-support).

@@ snip [#jackson-xml-support] ($root$/src/test/java/docs/http/javadsl/JacksonXmlSupport.java) { #jackson-xml-support }

The custom XML (un)marshalling code shown above requires that you depend on the `jackson-dataformat-xml` library.

@@@note
The Akka dependencies are available from Akka’s secure library repository. To access them you need to use a secure, tokenized URL as specified at https://account.akka.io/token.
@@@

Additionally, add the dependency as below.

@@dependency [sbt,Gradle,Maven] {
  group="com.fasterxml.jackson.dataformat"
  artifact="jackson-dataformat-xml"
  version="$jackson.xml.version$"
}

@@@

@@@ div { .group-scala }

For XML Akka HTTP currently provides support for [Scala XML][scala-xml] right out of the box through it's
`akka-http-xml` module.

## Scala XML Support

The @scaladoc[ScalaXmlSupport](akka.http.scaladsl.marshallers.xml.ScalaXmlSupport) trait provides a `FromEntityUnmarshaller[NodeSeq]` and `ToEntityMarshaller[NodeSeq]` that
you can use directly or build upon.

In order to enable support for (un)marshalling from and to XML with [Scala XML][scala-xml] `NodeSeq` you must add
the following dependency:

@@dependency [sbt,Gradle,Maven] {
  bomGroup2="com.typesafe.akka" bomArtifact2="akka-http-bom_$scala.binary.version$" bomVersionSymbols2="AkkaHttpVersion"
  symbol="AkkaHttpVersion"
  value="$project.version$"
  group="com.typesafe.akka"
  artifact="akka-http-xml_$scala.binary.version$"
  version="AkkaHttpVersion"
}

Once you have done this (un)marshalling between XML and `NodeSeq` instances should work nicely and transparently,
by either using `import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._` or mixing in the
`akka.http.scaladsl.marshallers.xml.ScalaXmlSupport` trait.

@@@

 [scala-xml]: https://github.com/scala/scala-xml
 [jackson]: https://github.com/FasterXML/jackson
