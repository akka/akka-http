resolvers += Classpaths.sbtPluginReleases
resolvers += Classpaths.typesafeReleases
resolvers += "lightbend-akka".at("https://repo.akka.io/maven/github_actions")

addSbtPlugin("com.github.sbt" % "sbt-multi-jvm" % "0.6.0")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
addSbtPlugin("com.github.sbt" % "sbt-boilerplate" % "0.7.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.3")
addSbtPlugin("com.lightbend.sbt" % "sbt-bill-of-materials" % "1.0.2")
addSbtPlugin("io.akka" % "sbt-paradox-akka" % "25.10.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.32")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.3")
addSbtPlugin("com.github.sbt" % "sbt-pull-request-validator" % "2.0.0")

// used for @unidoc directive
libraryDependencies += "io.github.lukehutch" % "fast-classpath-scanner" % "3.1.15"
