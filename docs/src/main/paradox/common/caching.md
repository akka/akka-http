# Caching

Akka HTTP's caching support provides a lightweight and fast in-memory caching
functionality based on futures. The primary use-case is the "wrapping" of an
expensive operation with a caching layer that, based on a certain key of type
`K`, runs the wrapped operation only once and returns the cached value for
all future accesses for the same key (as long as the respective entry has not
expired).

Akka HTTP comes with one implementation of the @apidoc[Cache] API built on [Caffeine]
featuring frequency-biased cache eviction semantics with support for
time-based entry expiration.

 [Caffeine]: https://github.com/ben-manes/caffeine/

## Dependency

@@@note
The Akka dependencies are available from Akka’s secure library repository. To access them you need to use a secure, tokenized URL as specified at https://account.akka.io/token.
@@@

To use Akka HTTP Caching, add the module to your project:

@@dependency [sbt,Gradle,Maven] {
  bomGroup2="com.typesafe.akka" bomArtifact2="akka-http-bom_$scala.binary.version$" bomVersionSymbols2="AkkaHttpVersion"
  symbol="AkkaHttpVersion"
  value="$project.version$"
  group="com.typesafe.akka"
  artifact="akka-http-caching_$scala.binary.version$"
  version="AkkaHttpVersion"
}

## Basic design

The central idea of the cache API is to not store the actual values of type `T`
themselves in the cache but rather the corresponding futures, i.e. instances of
type @java[`CompletableFuture<T>`]@scala[`Future[T]`]. This approach has the
advantage of taking care of the thundering herds problem where many
requests to a particular cache key (e.g. a resource URI) arrive before the first
one could be completed. Normally (without special guarding techniques, like
so-called "cowboy" entries) this can cause many requests to compete for system
resources while trying to compute the same result thereby greatly reducing
overall system performance. When you use an Akka HTTP cache the very first
request that arrives for a certain cache key causes a future to be put into the
cache which all later requests then "hook into". As soon as the first request
completes all other ones complete as well. This minimizes processing time and
server load for all requests.

All Akka HTTP cache implementations adheres to the @apidoc[Cache]
@java[interface]@scala[class], which allows you to interact with the
cache.

Along with the cache API, the routing DSL provides several @ref:[caching
directives](../routing-dsl/directives/caching-directives/index.md) to use
caching in your routes.

## Frequency-biased LFU cache

The frequency-biased LFU cache implementation has a defined maximum number of entries it can
store. After the maximum capacity is reached the cache will evict entries that are
less likely to be used again. For example, the cache may evict an entry
because it hasn't been used recently or very often.

Time-based entry expiration is enabled when time-to-live and/or time-to-idle
expirations are set to a finite duration. The former provides an
upper limit to the time period an entry is allowed to remain in the cache while
the latter limits the maximum time an entry is kept without having been
accessed, ie. either read or updated. If both values are finite the time-to-live
has to be greater or equal than the time-to-idle.

@@@ note

Expired entries are only evicted upon next access (or by being thrown out by the
capacity constraint), so they might prevent garbage collection of their values
for longer than expected.

@@@

For simple cases, configure the capacity and expiration settings in your
`application.conf` file via the settings under `akka.http.caching` and use
@java[`LfuCache.create()`]@scala[`LfuCache.apply()`] to create the cache.
For more advanced usage you can create an @apidoc[LfuCache$] with settings
specialized for your use case:

Java
:  @@snip [CachingDirectivesExamplesTest.java]($root$/src/test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #create-cache-imports #caching-directives-import #time-unit-import #keyer-function #create-cache }

Scala
:  @@snip [CachingDirectivesExamplesSpec.java]($root$/src/test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #keyer-function #create-cache }
