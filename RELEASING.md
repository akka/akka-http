## Releasing

1. Add a release notes entry in docs/src/main/paradox/release-notes.md. As a helper run
`scripts/commits-for-release-notes.sh <last-version-tag>` which will output a list of commits grouped by submodule.
2. Create a [new release](https://github.com/akka/akka-http/releases/new) with the next tag version (e.g. `v13.3.7`), title and release description including notable changes mentioning external contributors.
3. Travis CI will start a [CI build](https://travis-ci.org/akka/akka-http/builds) for the new tag and publish artifacts to Bintray and will sync them to Maven Central.
4. Checkout the newly created tag and run `sbt -Dakka.genjavadoc.enabled=true "++2.12.2 deployRsync repo.akka.io"` to deploy API and reference documentation.
5. Go to https://bintray.com/akka/maven/com.typesafe.akka:akka-http_2.11 and select the just released version
6. Go to the Maven Central tab and sync with Sonatype
   - (Optional, should happen automatically if selected in Bintray) Log in to Sonatype to Close the staging repository
   - Run a test against the staging repository to make sure the release went well
   - Release the staging repository to Maven Central.
7. Create a news item on https://github.com/akka/akka.github.com
8. Add the released version to `project/MiMa.scala` to the `mimaPreviousArtifacts` key.
9. Send a release notification to akka-user and tweet using the akka account (or ask someone to) about the new release

### Follow up steps

1. Update Akka HTTP dependency on akka.io website: https://github.com/akka/akka.github.com/blob/master/_config.yml
2. Update Akka HTTP dependency in akka-http seed templates ([scala](https://github.com/akka/akka-http-scala-seed.g8/) & [java](https://github.com/akka/akka-http-java-seed.g8/)) 
3. Update Akka HTTP dependency in [akka-management](https://github.com/akka/akka-management/blob/master/project/Dependencies.scala)

### Under the Travis hood

Here is what happens in detail when Travis CI is building a git tagged commit:

1. According to the `.travis.yml` file `deploy` section it first runs a `+publish` task:
  1. Because of the `+` a the start of the task, all of these actions are repeated for every scala version defined in `crossScalaVersions`.
  2. Because of the `bintray-sbt` plugin, `publish` builds and uploads artifacts to Bintray.
  3. By default all projects have `BintrayPlugin` enabled. Projects that have `disablePlugins(BintrayPlugin)` are not built.
  4. Artifacts are uploaded to `https://bintray.com/$bintrayOrganization/$bintrayRepository/$bintrayPackage` which in this case is [https://bintray.com/akka/maven/akka-http](https://bintray.com/akka/maven/akka-http).
  5. Credentials for the `publish` task are read from the `BINTRAY_USER` and `BINTRAY_PASS` environment variables which are stored encrypted on the `.travis.yml` file. The user under these credentials must be part of the [Akka team on Bintray](https://bintray.com/akka/).
  
# Pushing to Maven Central
This could be done automatically via `.travis.yml` in `deploy` by adding section is `akka-http/bintraySyncMavenCentral`, however we prefer to run this step manually after confirming a release is valid.

  1. This task syncs all of the artifacts under the [Akka Http](https://bintray.com/akka/maven/akka-http) package in Bintray to Maven Central. For the sync to be successful, the package first needs to be added to JCenter repository. This must be done through Bintray Web interface, and only once when the package is created.
  2. This task is only ran for one project, because all Akka Http projects are published to a single package on Bintray.
  3. Credentials for the `bintraySyncMavenCentral` task are read from the `SONATYPE_USER` and `SONATYPE_PASS` environment variables which are stored encrypted on the `.travis.yml` file. The user under these credentials must have the rights to publish artifacts to the Maven Central under the `com.typesafe.akka` organization name.
