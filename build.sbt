import akka._
import akka.ValidatePullRequest._
import AkkaDependency._
import Dependencies.{ h2specName, h2specExe }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import java.nio.file.Files
import java.nio.file.attribute.{ PosixFileAttributeView, PosixFilePermission }
import sbtdynver.GitDescribeOutput
import spray.boilerplate.BoilerplatePlugin
import com.lightbend.paradox.apidoc.ApidocPlugin.autoImport.apidocRootPackage

inThisBuild(Def.settings(
  organization := "com.typesafe.akka",
  organizationName := "Lightbend",
  organizationHomepage := Some(url("https://www.lightbend.com")),
  homepage := Some(url("https://akka.io")),
  // https://github.com/dwijnand/sbt-dynver/issues/23
  isSnapshot :=  { isSnapshot.value || hasCommitsAfterTag(dynverGitDescribeOutput.value) },
  apiURL := {
    val apiVersion = if (isSnapshot.value) "current" else version.value
    Some(url(s"https://doc.akka.io/api/akka-http/$apiVersion/"))
  },
  scmInfo := Some(
    ScmInfo(url("https://github.com/akka/akka-http"), "git@github.com:akka/akka-http.git")),
  developers := List(
    Developer("contributors", "Contributors", "akka-user@googlegroups.com",
      url("https://github.com/akka/akka-http/graphs/contributors"))
  ),
  startYear := Some(2014),
  //  test in assembly := {},
  licenses := Seq("Apache-2.0" -> url("https://opensource.org/licenses/Apache-2.0")),
  description := "Akka Http: Modern, fast, asynchronous, streaming-first HTTP server and client.",
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8", // yes, this is 2 args
    "-target:jvm-1.8",
    "-unchecked",
    "-Xlint",
    // "-Yno-adapted-args", //akka-http heavily depends on adapted args and => Unit implicits break otherwise
    "-Ywarn-dead-code"
    // "-Xfuture" // breaks => Unit implicits
  ),
  javacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-source", "1.8",
  ),
  javacOptions in (Compile, compile) ++= Seq("-target", "1.8"), // sbt #1785, avoids passing to javadoc
  testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
  Dependencies.Versions,
  Formatting.formatSettings,
  shellPrompt := { s => Project.extract(s).currentProject.id + " > " },
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
))

lazy val root = Project(
    id = "akka-http-root",
    base = file(".")
  )
  .enablePlugins(UnidocRoot, NoPublish, DeployRsync, AggregatePRValidation)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .settings(
    // Unidoc doesn't like macro definitions
    unidocProjectExcludes := Seq(parsing),
    // Support applying macros in unidoc:
    scalaMacroSupport,
    unmanagedSources in (Compile, headerCreate) := (baseDirectory.value / "project").**("*.scala").get,
    deployRsyncArtifact := {
      val unidocArtifacts = (unidoc in Compile).value
      // unidoc returns a Seq[File] which contains directories of generated API docs, one for
      // Java, one for Scala. It's not specified which is which, though.
      // We currently expect the java documentation at akka-http/target/javaunidoc, so
      // the following heuristic is hopefully good enough to determine which one is the Java and
      // which one the Scala version.
      val (Seq(java), Seq(scala)) = unidocArtifacts.partition(_.getName contains "java")

      Seq(
        scala -> s"www/api/akka-http/${version.value}",
        java -> s"www/japi/akka-http/${version.value}")
    }
  )
  .aggregate(
    // When this is or other aggregates are updated the set of modules in HttpExt.allModules should also be updated
    parsing,
    httpCore,
    http2Support,
    http,
    httpCaching,
    httpTestkit,
    httpTests,
    httpMarshallersScala,
    httpMarshallersJava,
    docs,
    compatibilityTests
  )

/**
 * Adds a `src/.../scala-2.13+` source directory for Scala 2.13 and newer
 * and a `src/.../scala-2.13-` source directory for Scala version older than 2.13
 */
def add213CrossDirs(config: Configuration): Seq[Setting[_]] = Seq(
  unmanagedSourceDirectories in config += {
    val sourceDir = (sourceDirectory in config).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => sourceDir / "scala-2.13+"
      case _                       => sourceDir / "scala-2.13-"
    }
  }
)

val commonSettings =
  add213CrossDirs(Compile) ++
  add213CrossDirs(Test)

val scalaMacroSupport = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 =>
        Seq("-Ymacro-annotations")
      case _                       =>
        Seq.empty
    }
  },
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) if n < 13 => Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
    case _                       => Seq.empty
  }),
)


lazy val parsing = project("akka-parsing")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http.parsing"))
  .addAkkaModuleDependency("akka-actor", "provided")
  .settings(Dependencies.parsing)
  .settings(
    scalacOptions := scalacOptions.value.filterNot(Set("-Xfatal-warnings", "-Xlint", "-Ywarn-dead-code").contains), // disable warnings for parboiled code
    scalacOptions += "-language:_",
    unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value
  )
  .settings(scalaMacroSupport)
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)

lazy val httpCore = project("akka-http-core")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http.core"))
  .dependsOn(parsing)
  .addAkkaModuleDependency("akka-stream", "provided")
  .addAkkaModuleDependency("akka-stream-testkit", "test")
  .settings(Dependencies.httpCore)
  .settings(VersionGenerator.versionSettings)
  .settings(scalaMacroSupport)
  .enablePlugins(BootstrapGenjavadoc)

lazy val http = project("akka-http")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http"))
  .dependsOn(httpCore)
  .addAkkaModuleDependency("akka-stream", "provided")
  .settings(Dependencies.http)
  .settings(
    scalacOptions in Compile += "-language:_"
  )
  .settings(scalaMacroSupport)
  .enablePlugins(BootstrapGenjavadoc, BoilerplatePlugin)

lazy val http2Support = project("akka-http2-support")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http.http2"))
  .dependsOn(httpCore, httpTestkit % "test", httpCore % "test->test")
  .addAkkaModuleDependency("akka-stream", "provided")
  .addAkkaModuleDependency("akka-stream-testkit", "test")
  .settings(Dependencies.http2)
  .settings(Dependencies.http2Support)
  .settings {
    lazy val h2specPath = Def.task {
      (target in Test).value / h2specName / h2specExe
    }
    Seq(
      javaAgents += Dependencies.Compile.Test.alpnAgent,
      fork in run in Test := true,
      fork in Test := true,
      sbt.Keys.connectInput in run in Test := true,
      javaOptions in Test += "-Dh2spec.path=" + h2specPath.value,
      resourceGenerators in Test += Def.task {
        val log = streams.value.log
        val h2spec = h2specPath.value

        if (!h2spec.exists) {
          log.info("Extracting h2spec to " + h2spec)

          for (zip <- (update in Test).value.select(artifact = artifactFilter(name = h2specName, extension = "zip")))
            IO.unzip(zip, (target in Test).value)

          // Set the executable bit on the expected path to fail if it doesn't exist
          for (view <- Option(Files.getFileAttributeView(h2spec.toPath, classOf[PosixFileAttributeView]))) {
            val permissions = view.readAttributes.permissions
            if (permissions.add(PosixFilePermission.OWNER_EXECUTE))
              view.setPermissions(permissions)
          }
        }
        Seq(h2spec)
      }
    )
  }
  .enablePlugins(JavaAgent, BootstrapGenjavadoc)
  .disablePlugins(MimaPlugin) // experimental module still

lazy val httpTestkit = project("akka-http-testkit")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http.testkit"))
  .dependsOn(http)
  .addAkkaModuleDependency("akka-stream-testkit", "provided")
  .addAkkaModuleDependency("akka-testkit", "provided")
  .settings(Dependencies.httpTestkit)
  .settings(
    // don't ignore Suites which is the default for the junit-interface
    testOptions += Tests.Argument(TestFrameworks.JUnit, "--ignore-runners="),
    scalacOptions in Compile ++= Seq("-language:_"),
    mainClass in run in Test := Some("akka.http.javadsl.SimpleServerApp")
  )
  .enablePlugins(BootstrapGenjavadoc, MultiNodeScalaTest, ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin) // testkit, no bin compat guaranteed

lazy val httpTests = project("akka-http-tests")
  .settings(commonSettings)
  .settings(Dependencies.httpTests)
  .dependsOn(httpSprayJson, httpXml, httpJackson,
    httpTestkit % "test", httpCore % "test->test")
  .enablePlugins(NoPublish).disablePlugins(BintrayPlugin) // don't release tests
  .enablePlugins(MultiNode)
  .disablePlugins(MimaPlugin) // this is only tests
  .configs(MultiJvm)
  .settings(headerSettings(MultiJvm))
  .settings(additionalTasks in ValidatePR += headerCheck in MultiJvm)
  .addAkkaModuleDependency("akka-stream", "provided")
  .addAkkaModuleDependency("akka-multi-node-testkit", "test")

lazy val httpJmhBench = project("akka-http-bench-jmh")
  .settings(commonSettings)
  .dependsOn(http)
  .addAkkaModuleDependency("akka-stream")
  .enablePlugins(JmhPlugin)
  .enablePlugins(NoPublish).disablePlugins(BintrayPlugin) // don't release benchs
  .disablePlugins(MimaPlugin)

lazy val httpMarshallersScala = project("akka-http-marshallers-scala")
  .settings(commonSettings)
  .enablePlugins(NoPublish/*, AggregatePRValidation*/)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .aggregate(httpSprayJson, httpXml)

lazy val httpXml =
  httpMarshallersScalaSubproject("xml")
    .settings(AutomaticModuleName.settings("akka.http.marshallers.scalaxml"))
    .addAkkaModuleDependency("akka-stream", "provided")
    .settings(Dependencies.httpXml)

lazy val httpSprayJson =
  httpMarshallersScalaSubproject("spray-json")
    .settings(AutomaticModuleName.settings("akka.http.marshallers.sprayjson"))
    .addAkkaModuleDependency("akka-stream", "provided")
    .settings(Dependencies.httpSprayJson)

lazy val httpMarshallersJava = project("akka-http-marshallers-java")
  .settings(commonSettings)
  .enablePlugins(NoPublish/*, AggregatePRValidation*/)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .aggregate(httpJackson)

lazy val httpJackson =
  httpMarshallersJavaSubproject("jackson")
    .settings(AutomaticModuleName.settings("akka.http.marshallers.jackson"))
    .addAkkaModuleDependency("akka-stream", "provided")
    .settings(Dependencies.httpJackson)
    .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val httpCaching = project("akka-http-caching")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("akka.http.caching"))
  .addAkkaModuleDependency("akka-stream", "provided")
  .addAkkaModuleDependency("akka-stream-testkit", "provided")
  .settings(Dependencies.httpCaching)
  .dependsOn(http, httpCore, httpTestkit % "test")
  .enablePlugins(BootstrapGenjavadoc)

def project(name: String) =
  Project(id = name, base = file(name))

def httpMarshallersScalaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-scala/akka-http-$name")
  )
  .dependsOn(http)
  .settings(commonSettings)
  .enablePlugins(BootstrapGenjavadoc)

def httpMarshallersJavaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-java/akka-http-$name"),
  )
  .dependsOn(http)
  .settings(commonSettings)
  .enablePlugins(BootstrapGenjavadoc)

lazy val docs = project("docs")
  .enablePlugins(AkkaParadoxPlugin, NoPublish, DeployRsync)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .addAkkaModuleDependency("akka-stream", "provided")
  .dependsOn(
    httpCore, http, httpXml, http2Support, httpMarshallersJava, httpMarshallersScala, httpCaching,
    httpTests % "compile;test->test", httpTestkit % "compile;test->test"
  )
  .settings(Dependencies.docs)
  .settings(
    name := "akka-http-docs",
    resolvers += Resolver.jcenterRepo,
    paradoxGroups := Map("Language" -> Seq("Scala", "Java")),
    paradoxProperties in Compile ++= Map(
      "project.name" -> "Akka HTTP",
      "canonical.base_url" -> "https://doc.akka.io/docs/akka-http/current",
      "akka.version" -> AkkaDependency.akkaVersion,
      "alpn-agent.version" -> Dependencies.alpnAgentVersion,
      "scala.binary_version" -> scalaBinaryVersion.value, // to be consistent with Akka build
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "scaladoc.version" -> scalaVersion.value,
      "crossString" -> (scalaVersion.value match {
        case akka.Doc.BinVer(_) => ""
        case _                  => "cross CrossVersion.full"
      }),
      "jackson.version" -> Dependencies.jacksonVersion,
      "extref.akka-docs.base_url" -> s"https://doc.akka.io/docs/akka/${AkkaDependency.akkaVersion}/%s",
      "extref.akka25-docs.base_url" -> s"https://doc.akka.io/docs/akka/2.5/%s",
      "javadoc.akka.http.base_url" -> {
        val v = if (isSnapshot.value) "current" else version.value
        s"https://doc.akka.io/japi/akka-http/$v"
      },
      "javadoc.akka.base_url" -> s"https://doc.akka.io/japi/akka/${AkkaDependency.akkaVersion}",
      "scaladoc.akka.http.base_url" -> {
        val v = if (isSnapshot.value) "current" else version.value
        s"https://doc.akka.io/api/akka-http/$v"
      },
      "scaladoc.akka.base_url" -> s"https://doc.akka.io/api/akka/${AkkaDependency.akkaVersion}",
      "algolia.docsearch.api_key" -> "0ccbb8bf5148554a406fbf07df0a93b9",
      "algolia.docsearch.index_name" -> "akka-http",
      "google.analytics.account" -> "UA-21117439-1",
      "google.analytics.domain.name" -> "akka.io",
      "github.base_url" -> GitHub.url(version.value),
      "snip.test.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
      "snip.akka-http.base_dir" -> (baseDirectory in ThisBuild).value.getAbsolutePath,
      "signature.test.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
      "signature.akka-http.base_dir" -> (baseDirectory in ThisBuild).value.getAbsolutePath
    ),
    apidocRootPackage := "akka",
    Formatting.docFormatSettings,
    additionalTasks in ValidatePR += paradox in Compile,
    deployRsyncArtifact := List((paradox in Compile).value -> s"www/docs/akka-http/${version.value}")
  )
  .settings(ParadoxSupport.paradoxWithCustomDirectives)

lazy val compatibilityTests = Project("akka-http-compatibility-tests", file("akka-http-compatibility-tests"))
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .addAkkaModuleDependency("akka-stream", "provided")
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % "10.1.8" % "provided", // TODO, should we make that latest?
    ),
    (dependencyClasspath in Test) := {
      // HACK: We'd like to use `dependsOn(http % "test->compile")` to upgrade the explicit dependency above to the
      //       current version but that fails. So, this is a manual `dependsOn` which works as expected.
      (dependencyClasspath in Test).value.filterNot(_.data.getName contains "akka") ++
      (fullClasspath in (httpTests, Test)).value
    }
  )

def hasCommitsAfterTag(description: Option[GitDescribeOutput]): Boolean = description.get.commitSuffix.distance > 0
