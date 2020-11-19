# 9. Contributing

## Welcome!

We follow the standard GitHub [fork & pull](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/about-pull-requests#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

For a more detailed description of our process, please refer to the [CONTRIBUTING.md](https://github.com/akka/akka-http/blob/master/CONTRIBUTING.md) page on the github project.

## Snapshots

Testing snapshot versions can help us find bugs before a release. We publish snapshot versions for every master commit.

The latest published snapshot version is [![bintray-badge][]][bintray].

### Configure repository

sbt
:   ```scala
    resolvers += Resolver.bintrayRepo("akka", "snapshots")
    ```

Maven
:   ```xml
    <project>
    ...
      <repositories>
        <repository>
          <id>akka-http-snapshots</id>
          <name>Akka HTTP Snapshots</name>
          <url>https://dl.bintray.com/akka/snapshots</url>
        </repository>
      </repositories>
    ...
    </project>
    ```

Gradle
:   ```gradle
    repositories {
      maven {
        url  "https://dl.bintray.com/akka/snapshots"
      }
    }
    ```

[bintray-badge]:  https://api.bintray.com/packages/akka/snapshots/akka-http/images/download.svg
[bintray]:        https://bintray.com/akka/snapshots/akka-http/_latestVersion