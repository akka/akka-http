Release Akka HTTP $VERSION$

<!--
Release Train Issue Template for Akka HTTP

(Liberally copied and adopted from Scala itself https://github.com/scala/scala-dev/blob/b11cd2e4a4431de7867db6b39362bea8fa6650e7/notes/releases/template.md)

For every release, use the `scripts/create-release-issue.sh` to make a copy of this file named after the release, and expand the variables.

Variables to be expanded in this template:
- VERSION=???

Key links:
  - akka/akka-http milestone: https://github.com/akka/akka-http/milestone/?
-->

### Cutting the release

- [ ] Check that open PRs and issues assigned to the milestone are reasonable
- [ ] If PRs were merged after EU midnight, trigger the [native-image tests](https://github.com/akka/akka-http/actions/workflows/native-image-tests.yml) and see that they are gr
- [ ] Update the Change date in the LICENSE file.
- [ ] Update the Akka HTTP version in the samples to $VERSION$, otherwise the published zip files of the samples will have the old version.
- [ ] For minor or major versions, add a release notes entry in `docs/src/main/paradox/release-notes/`.
- [ ] Create a new milestone for the [next version](https://github.com/akka/akka-http/milestones)
- [ ] Close the [$VERSION$ milestone](https://github.com/akka/akka-http/milestones?direction=asc&sort=due_date)
- [ ] Make sure all important PRs have been merged
- [ ] For recent dependency updates or changes on a minor release branch the Fossa validation can be triggered from the GitHub actions "Dependency License Scanning" (Manually choosing the release branch)
- [ ] Update the revision in Fossa in the Akka Group for the Akka umbrella version, e.g. `22.10`. Note that the revisions for the release is udpated by Akka Group > Projects > Edit. For recent dependency updates the Fossa validation can be triggered from the GitHub actions "Dependency License Scanning".
- [ ] Wait until [main build finished](https://github.com/akka/akka-http/actions) after merging the latest PR
- [ ] Update the [draft release](https://github.com/akka/akka-http/releases) with the next tag version `v$VERSION$`, title and release description. Use the `Publish release` button, which will create the tag.
- [ ] Check that GitHub Actions release build has executed successfully (GitHub Actions will start a [CI build](https://github.com/akka/akka-http/actions) for the new tag and publish artifacts to https://repo.akka.io/maven)

### Check availability

- [ ] Check [API](https://doc.akka.io/api/akka-http/$VERSION$/) documentation
- [ ] Check [reference](https://doc.akka.io/docs/akka-http/$VERSION$/) documentation. Check that the reference docs were deployed and show a version warning (see section below on how to fix the version warning).
- [ ] Check the release on https://repo.akka.io/maven/com/typesafe/akka/akka-http-core_2.13/$VERSION$/akka-http-core_2.13-$VERSION$.pom

### When everything is on https://repo.akka.io/maven
  - [ ] Log into `gustav.akka.io` as `akkarepo` 
    - [ ] If this updates the `current` version, run `./update-akka-http-current-version.sh $VERSION$`
    - [ ] otherwise check changes and commit the new version to the local git repository
         ```
         cd ~/www
         git status
         git add docs/akka-http/current docs/akka-http/$VERSION$
         git add api/akka-http/current api/akka-http/$VERSION$
         git add japi/akka-http/current japi/akka-http/$VERSION$
         git commit -m "Akka HTTP $VERSION$"
         ```
    - [ ] push changes to the [remote git repository](https://github.com/akka/doc.akka.io)
         ```
         cd ~/www
         git push origin master
         ```

  - [ ] If this updated 'current' docs - trigger a re-index of the docs for search through [Run workflow for the scraper](https://github.com/akka/akka-http/actions/workflows/algolia-doc-site-scrape.yml)
  - [ ] Update version in _config.yml in https://github.com/akka/akka.io

### Announcements

For important patch releases, and only if critical issues have been fixed:

- [ ] Send a release notification to [Lightbend discuss](https://discuss.akka.io)
- [ ] Tweet using the [@akkateam](https://twitter.com/akkateam/) account (or ask someone to) about the new release
- [ ] Announce internally (with links to Tweet, discuss)

For minor or major releases:

- [ ] Include noteworthy features and improvements in Akka umbrella release announcement at akka.io. Coordinate with PM and marketing.

### Afterwards

- [ ] Add the released version to `project/MiMa.scala` to the `mimaPreviousArtifacts` key *of all current compatible branches*.
- [ ] Update [akka-dependencies bom](https://github.com/lightbend/akka-dependencies) and version for [Akka module versions](https://doc.akka.io/libraries/akka-dependencies/current/) in [akka-dependencies repo](https://github.com/akka/akka-dependencies)
- [ ] Update [Akka Guide samples](https://github.com/lightbend/akka-guide)
- [ ] Update sbt new (g8) template:
  - [ ] [Akka HTTP Scala](https://github.com/akka/akka-http-quickstart-scala.g8/blob/main/src/main/g8/default.properties)

- Close this issue
