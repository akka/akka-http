commands ++= Seq(
  // check policies, if on master also upload
  Command.command("whitesourceCi") { state =>
    val extracted = Project.extract(state)
    import extracted._

    if (sys.env.getOrElse("TRAVIS_SECURE_ENV_VARS", "") == "true") {
      val stateWithCredentials =
        append(credentials += Credentials("whitesource", "whitesourcesoftware.com", "", System.getenv("WHITESOURCE_KEY")), state)

      runTask(whitesourceCheckPolicies, stateWithCredentials)

      if (sys.env.getOrElse("TRAVIS_BRANCH", "") == "master" && sys.env.getOrElse("TRAVIS_EVENT_TYPE", "") == "push") {
        runTask(whitesourceUpdate, stateWithCredentials)
      }
    }

    state
  },

  // publish snapshot to bintray snapshots repo
  Command.command("publishCi") { state =>
    val extracted = Project.extract(state)
    import extracted._

    if (sys.env.getOrElse("TRAVIS_EVENT_TYPE", "") == "cron") {
      runAggregated(ThisBuild / publish, state)
    }

    state
  }
)
