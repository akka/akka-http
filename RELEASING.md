## Releasing

Create a new issue from the [Akka HTTP Release Train Issue Template](scripts/release-train-issue-template.md) by running `scripts/create-release-issue.sh` and follow the steps.

A few more background information about the process can be found below.

### Release Automation with Github Action

Akka HTTP uses Github Actions and to release artifacts automatically to Akka's library repository. For commits to the `main` branch, it publishes artifacts to the snapshot repository.

### Releasing only updated docs

It is possible to release a revised documentation to the already existing release.

1. Create a new branch from a release tag. If a revised documentation is for the `v10.2.4` release, then the name of the new branch should be `docs/v10.2.4`:
    ```
    $ git checkout v10.2.4
    $ git checkout -b docs/v10.2.4
    ```
1. Add and commit `version.sbt` file that pins the version to the one that is being revised. Also set `isSnapshot` to `false` for the stable documentation links. For example:
    ```scala
    ThisBuild / version := "10.2.4"
    ThisBuild / isSnapshot := false
    ```
1. Make or cherry-pick updates to the docs
1. Build documentation locally with:
    ```sh
    sbt akka-docs/paradoxBrowse
    ```
1. Don't forget to commit and push
1. Upload the docs:
    ```sh
    sbt docs/publishRsync
    ```
