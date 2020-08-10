The setup of the scalafix module roughly follows the example in https://github.com/scalacenter/scalafix.g8.

## Adding new rules

 * Add before/after test file in scalafix-test-input / scalafix-test-output
 * Add rule in scalafix-rules
 * run test in `akka-http-scalafix-tests`

## Applying locally defined rules to docs examples

 * run `scalafixEnable` on the sbt shell (this will unfortunately require a complete rebuild afterwards)
 * run `set scalacOptions in ThisBuild += "-P:semanticdb:synthetics:on"` to allow access to synthetics
 * e.g. run `docs/scalafixAll MigrateToServerBuilder`

*Note:* There's some weird stuff going on regarding cross-publishing. The `scalafixScalaBinaryVersion` line in build.sbt
should fix it but if running the rule fails with a weird error, try switching to Scala 2.12 first with `++2.12.11` (or
whatever is now the current version).
