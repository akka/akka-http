Release Train Issue Template for Akka HTTP

(Liberally copied and adopted from Scala itself https://github.com/scala/scala-dev/blob/b11cd2e4a4431de7867db6b39362bea8fa6650e7/notes/releases/template.md)

For every Akka Http release, make a copy of this file named after the release, and expand the variables.
Ideally replacing variables could become a script you can run on your local machine.

Variables to be expanded in this template:
- AKKA_HTTP_VER=??? (currently not used)

Key links:
  - akka/akka-http milestone: https://github.com/akka/akka-http/milestone/?

### ~ 1 week before the release
- [ ] Check that open PRs and issues assigned to the milestone are reasonable
- [ ] Triage tickets that should be ready for this release, add "pick next" label and release milestone
- [ ] Triage open PRs and apply "pick next" label and maybe add to release milestone. Some PRs might be explicitly scheduled for this release, others might be ready enough to bring them over the finish line 
Wind down PR queue. There has to be enough time after the last (non-trivial) PR is merged and the next phase. The core of the eco-system needs time to prepare for the final!
- [ ] Decide on planned release date
- [ ] Notify depending projects (notably Play + cinnamon) about upcoming release

### 1 day before the release
- [ ] Make sure all important / big PRs have been merged by now
- [ ] Check that latest nightly (once we have that) / master still works with depending projects (notably Play + cinnamon)
- [ ] Communicate that a release is about to be released in [Gitter Akka Dev Channel](https://gitter.im/akka/dev), so that no new Pull Requests are merged

### Preparing release notes in the documentation / announcement

- [ ] If this is a new minor (not patch) release, rename the 'akka-http-x.x-stable' reporting project in [WhiteSource](https://saas.whitesourcesoftware.com/) accordingly
- [ ] Add a release notes entry in docs/src/main/paradox/release-notes/. As a helper run `scripts/commits-for-release-notes.sh <last-version-tag>` which will output a list of commits grouped by submodule, and the closed issues for this milestone.
- [ ] Create a PR on https://github.com/akka/akka.github.com with this
  - a news item draft, using the milestones and `scripts/authors.scala previousVersion thisVersion`
  - update Akka HTTP dependency for akka.io website: https://github.com/akka/akka.github.com/blob/master/_config.yml
- [ ] Release notes PR has been merged
- [ ] Create a new milestone for the next version at https://github.com/akka/akka-http/milestones
- [ ] Move all unclosed issues to the newly created milestone (or remove milestone) and close the version you're releasing

### Cutting the release

- [ ] Make sure there are no stray staging repos on sonatype
- [ ] Wait until [master build finished](https://travis-ci.org/akka/akka-http/builds/) after merging the release notes
- [ ] Create a [new release](https://github.com/akka/akka-http/releases/new) with the next tag version (e.g. `v13.3.7`), title and release description linking to announcement, release notes and milestone.
- [ ] Check that the Travis CI release build has executed successfully
- [ ] Go to https://bintray.com/akka/maven/com.typesafe.akka:akka-http_2.11 and select the just released version
- [ ] Go to the Maven Central tab and sync with Sonatype
- [ ] Log in to Sonatype to Close the staging repository (optional, should happen automatically if selected in Bintray)
- [ ] Notify Telemetry / Play team to check against staged artifacts
- [ ] Run a test against the staging repository to make sure the release went well, for example by using https://github.com/akka/akka-http-scala-seed.g8 and adding the sonatype staging repo with `resolvers += "Staging Repo" at "https://oss.sonatype.org/content/repositories/comtypesafe-xxx"`
- [ ] Release the staging repository to Maven Central.
- [ ] Checkout the newly created tag and run `sbt -Dakka.genjavadoc.enabled=true ++2.12.8 "deployRsync akkarepo@gustav.akka.io"` to deploy API and reference documentation.

### Check availability
- [ ] Check release on sonatype: https://oss.sonatype.org/content/repositories/releases/com/typesafe/akka/akka-http-core_2.11/
- [ ] Check the release on maven central: http://central.maven.org/maven2/com/typesafe/akka/akka-http-core_2.11/

### When everything is on maven central
- [ ] Log into gustav.akka.io as akkarepo and update the `current` links on repo.akka.io to point to the latest version with `ln -nsf <latestversion> www/docs/akka-http/current; ln -nsf <latestversion> www/api/akka-http/current; ln -nsf <latestversion> www/japi/akka-http/current`.

### Announcements
- [ ] Merge draft news item at https://github.com/akka/akka.github.com
- [ ] Send a release notification to https://discuss.akka.io
- [ ] Tweet using the akka account (or ask someone to) about the new release
- [ ] Announce on Gitter at https://gitter.im/akka/akka

### Afterwards
- [ ] Add the released version to `project/MiMa.scala` to the `mimaPreviousArtifacts` key *of all current compatible branches*.
- [ ] Update Akka HTTP dependency in [akka-management](https://github.com/akka/akka-management/edit/master/project/Dependencies.scala)
- [ ] Update Akka HTTP reference in [reactive-platform-docs](https://github.com/typesafehub/reactive-platform-docs/edit/master/build.sbt#L29)
- Close this issue
