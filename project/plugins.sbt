resolvers += Classpaths.sbtPluginReleases
resolvers += Classpaths.typesafeReleases
resolvers += Resolver.sonatypeRepo("releases") // to more quickly obtain paradox rigth after release

// need this to resolve https://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += Resolver.jcenterRepo

addSbtPlugin("com.github.sbt" % "sbt-multi-jvm" % "0.6.0")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.3")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.0.1")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1") // for advanced PR validation features
addSbtPlugin("com.github.sbt" % "sbt-boilerplate" % "0.7.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.3")
addSbtPlugin("com.lightbend.sbt" % "sbt-bill-of-materials" % "1.0.2")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.57")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.32")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.0")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.12")

// used in ValidatePullRequest to check github PR comments whether to build all subprojects
libraryDependencies += "org.kohsuke" % "github-api" % "1.321"

// used for @unidoc directive
libraryDependencies += "io.github.lukehutch" % "fast-classpath-scanner" % "3.1.15"
