## Releasing

Create a new issue from the [Akka HTTP Release Train Issue Template](scripts/release-train-issue-template.md) by running `scripts/create-release-issue.sh` and follow the steps.

A few more background information about the process can be found below.

### Release Automation with Github Action

Akka HTTP uses Github Actions and the sbt-ci-release plugin to release artifacts automatically. For commits to the `main` branch, it directly publishes artifacts to the Sonatype snapshot repository.
Tags are published to Maven Central. The process is currently a two-step process:
 * Github Actions uses sbt-ci-release to prepare the release and push it to Sonatype, closing the repository at the end.
 * At that point a staging repository has been created that can be used to validate artifacts.
 * When the release has been validated, the release person needs to manually release the artifacts from staging to Maven Central.
