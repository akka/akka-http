import akka._

Dependencies.http2

fork in run in Test := true
connectInput in run in Test := true

javaOptions in run in Test += "-javaagent:jetty-alpn-agent-2.0.4.jar"

libraryDependencies +=
  //"io.netty" % "netty-tcnative" % "1.1.33.Fork22" % Runtime classifier "linux-x86_64"
  "io.netty" % "netty-tcnative-boringssl-static" % "1.1.33.Fork22" % Runtime classifier "linux-x86_64"
