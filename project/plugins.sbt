resolvers += Classpaths.sbtPluginReleases
resolvers += Classpaths.typesafeReleases
resolvers += Resolver.sonatypeRepo("releases") // to more quickly obtain paradox rigth after release

// need this to resolve https://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += Resolver.jcenterRepo

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.9.2")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1") // for advanced PR validation features
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.2")
addSbtPlugin("com.lightbend.sbt" % "sbt-bill-of-materials" % "1.0.2")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.38")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.25")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.29")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")

// used in ValidatePullRequest to check github PR comments whether to build all subprojects
libraryDependencies += "org.kohsuke" % "github-api" % "1.131"

// used for @unidoc directive
libraryDependencies += "io.github.lukehutch" % "fast-classpath-scanner" % "3.1.15"
