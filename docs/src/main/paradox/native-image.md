# Building Native Images


Building native images with Akka HTTP is supported.

For details about building native images with Akka in general, see the @extref[Akka Documentation](akka-docs:additional/native-image.html)

Most built-in features in Akka HTTP can be used as is, however some require additional metadata for reflective access to work.

## Unsupported features

The following features cannot be used in native image apps:

 * Testkits

## Features requiring additional metadata

In general, any feature that allows a custom implementation provided by a third party library or user code that is
then plugged into Akka HTTP through an entry in the application config file needs to have reflection metadata explicitly added
as described in the [GraalVM docs here](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).


## Marshalling

### Akka HTTP Spray JSON

If using Akka HTTP Spray JSON for marshalling via the `jsonFormatN` factories for `RootJsonFormat` (or corresponding ``) to automatically choose
JSON field names from the class field names each such class will need a reflect-config metadata entry in your application 
like this:

```json
 {
    "name":"com.example.MyClass",
    "allDeclaredFields":true,
    "queryAllPublicMethods":true
  }
```

An alternative is to use the `jsonFormat` factories where field names are explicitly passed as parameters, such formats
should not need any reflection metadata.

### Akka HTTP Jackson

If using Akka HTTP Jackson for marshalling, each application class used for marshalling to JSON and unmarshalling from JSON
needs a reflect-config entry in your application like this:

```json
  {
    "name":"com.example.MyClass",
    "allDeclaredConstructors" : true,
    "allPublicConstructors" : true,
    "allDeclaredMethods" : true,
    "allPublicMethods" : true,
    "allDeclaredFields" : true,
    "allPublicFields" : true
  }
```

## Custom parsing error handler

If defining a custom @apidoc[akka.http.ParsingErrorHandler] through the config `akka.http.server.parsing.error-handler`
the error handler implementation needs a reflection metadata entry allowing class lookup and construction via a no-parameter constructor.
