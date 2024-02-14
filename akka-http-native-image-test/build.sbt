lazy val akkaHttpVersion = sys.props.getOrElse("akka.http.version", "10.6.0")
lazy val akkaVersion    = sys.props.getOrElse("akka.version", "2.9.1")

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

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
    name := "akka-http-native-image-test",
    // useful for investiations
    // javaOptions += "-agentlib:native-image-agent=config-output-dir=/Users/johan/Code/Lightbend/Akka/akka-http/akka-http-native-image-test/target/",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"                % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json"     % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
      "com.typesafe.akka" %% "akka-stream"              % akkaVersion,
      "ch.qos.logback"    % "logback-classic"           % "1.2.13",

      "com.typesafe.akka" %% "akka-http-testkit"        % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion     % Test,
      "org.scalatest"     %% "scalatest"                % "3.2.15"        % Test
    ),
    // GraalVM native image build
    nativeImageJvm := "graalvm-community",
    nativeImageVersion := "21.0.2",
    nativeImageOptions := Seq(
      "--no-fallback",
      "--verbose",
      "--initialize-at-build-time=ch.qos.logback,org.slf4j.LoggerFactory,org.slf4j.MDC",
      "-Dlogback.configurationFile=logback.xml" // configured at build time
    )
  )
