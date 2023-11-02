# 9. Contributing

## Welcome!

We follow the standard GitHub [fork & pull](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/about-pull-requests#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

For a more detailed description of our process, please refer to the [CONTRIBUTING.md](https://github.com/akka/akka-http/blob/main/CONTRIBUTING.md) page on the github project.

## Snapshots

Testing snapshot versions can help us find bugs before a release. We publish snapshot versions for every commit to the `main` branch.

Snapshot builds are available at https://repo.akka.io/snapshots. All Akka modules that belong to the same build have the same version.

### Configure repository

sbt
:   ```scala
    resolvers += "Akka library snapshot repository".at("https://repo.akka.io/snapshots")
    ```

Maven
:   ```xml
    <project>
    ...
      <repositories>
        <repositories>
          <repository>
            <id>akka-repository</id>
            <name>Akka library snapshot repository</name>
            <url>https://repo.akka.io/snapshots</url>
          </repository>
        </repositories>
      </repositories>
    ...
    </project>
    ```

Gradle
:   ```gradle
    repositories {
      maven {
        url  "https://repo.akka.io/snapshots"
      }
    }
    ```

