# 9. Contributing

## Welcome!

We follow the standard GitHub [fork & pull](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/about-pull-requests#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

For a more detailed description of our process, please refer to the [CONTRIBUTING.md](https://github.com/akka/akka-http/blob/main/CONTRIBUTING.md) page on the github project.

## Snapshots

Testing snapshot versions can help us find bugs before a release. We publish snapshot versions for every commit to the `main` branch.

The latest published snapshot version is [![SonatypeSnapshots-badge][]][SonatypeSnapshots].

### Configure repository

sbt
:   ```scala
    resolvers += "akka-http-snapshot-repository" at "https://oss.sonatype.org/content/repositories/snapshots"
    ```

Maven
:   ```xml
    <project>
    ...
      <repositories>
        <repository>
          <id>akka-http-snapshots</id>
          <name>Akka HTTP Snapshots</name>
          <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        </repository>
      </repositories>
    ...
    </project>
    ```

Gradle
:   ```gradle
    repositories {
      maven {
        url  "https://oss.sonatype.org/content/repositories/snapshots"
      }
    }
    ```

[SonatypeSnapshots-badge]:  https://img.shields.io/nexus/s/https/oss.sonatype.org/com.typesafe.akka/akka-http-core_2.13.svg
[SonatypeSnapshots]:        https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/akka-http-core_2.13/
