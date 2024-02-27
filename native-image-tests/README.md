# Project used to verify native-image support works, run by CI or locally.

## Running locally

`sbt publishLocal` akka-http itself.

Build test project with `sbt -Dakka.http.version=[local-snapshot-version] nativeImage` and then start the generated native
image.

  