// note: not important to keep these up to date, CI will always bump to correct versions
lazy val akkaHttpVersion = sys.props.getOrElse("akka.http.version", "10.7.1")
lazy val akkaVersion    = sys.props.getOrElse("akka.version", "2.10.11")

ThisBuild / resolvers += "lightbend-akka".at("https://repo.akka.io/maven/github_actions")
ThisBuild / credentials ++= {
  val path = Path.userHome / ".sbt" / ".credentials"
  if (path.isFile) {
    Seq(Credentials(path))
  } else Nil
}

// Run in a separate JVM, to make sure sbt waits until all threads have
// finished before returning.
// If you want to keep the application running while executing other
// sbt tasks, consider https://github.com/spray/sbt-revolver/
fork := true

lazy val root = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    inThisBuild(List(
      organization    := "com.lightbend",
      scalaVersion    := "2.13.12"
    )),
    name := "native-image-tests",
    // useful for investigations, needs to be run on graalvm JDK
    // javaOptions += "-agentlib:native-image-agent=config-output-dir=target/generated-native-image-metadata",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"                % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json"     % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-jackson"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-caching"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
      "com.typesafe.akka" %% "akka-stream"              % akkaVersion,
      "ch.qos.logback"    % "logback-classic"           % "1.5.7",

      "com.typesafe.akka" %% "akka-http-testkit"        % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion     % Test,
      "org.scalatest"     %% "scalatest"                % "3.2.15"        % Test
    ),
    // for jackson
    javacOptions += "-parameters",
    // GraalVM native image build
    nativeImageJvm := "graalvm-community",
    nativeImageVersion := "21.0.2",
    nativeImageOptions := Seq(
      "--no-fallback",
      "--verbose",
      "--initialize-at-build-time=ch.qos.logback"
    )
  )
