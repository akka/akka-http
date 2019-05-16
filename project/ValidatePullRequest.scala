/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io._

import akka.MimaWithPrValidation.{MimaResult, NoErrors, Problems}
import net.virtualvoid.sbt.graph.ModuleGraph
import net.virtualvoid.sbt.graph.backend.SbtUpdateReport
import org.kohsuke.github.GHIssueComment
import org.kohsuke.github.GitHubBuilder
import sbt.Keys._
import sbt._
import sbt.access.Aggregation
import sbt.access.Aggregation.Complete
import sbt.access.Aggregation.KeyValue
import sbt.access.Aggregation.ShowConfig
import sbt.internal.util.MainAppender
import sbt.internal.Act
import sbt.internal.BuildStructure
import sbt.std.Transform.DummyTaskMap
import sbt.util.LogExchange
import sbtunidoc.BaseUnidocPlugin.autoImport.unidoc

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.sys.process._
import scala.util.Try
import scala.util.matching.Regex

object ValidatePullRequest extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  sealed trait BuildMode {
    def task: Option[TaskKey[_]]
    def log(projectName: String, l: Logger): Unit
  }

  case object BuildSkip extends BuildMode {
    override def task = None
    def log(projectName: String, l: Logger) =
      l.info(s"Skipping validation of [$projectName], as PR does NOT affect this project...")
  }

  case object BuildQuick extends BuildMode {
    override def task = Some(executeTests in ValidatePR)
    def log(projectName: String, l: Logger) =
      l.info(s"Building [$projectName] in quick mode, as its dependencies were affected by PR.")
  }

  case object BuildProjectChangedQuick extends BuildMode {
    override def task = Some(executeTests in ValidatePR)
    def log(projectName: String, l: Logger) =
      l.info(s"Building [$projectName] as the root `project/` directory was affected by this PR.")
  }

  final case class BuildCommentForcedAll(phrase: String, c: GHIssueComment) extends BuildMode {
    override def task = Some(executeTests in Test)
    def log(projectName: String, l: Logger) =
      l.info(s"GitHub PR comment [${c.getUrl}] contains [$phrase], forcing BUILD ALL mode!")
  }

  val ValidatePR = config("pr-validation") extend Test

  override lazy val projectConfigurations = Seq(ValidatePR)

  /**
    * Assumptions:
    * Env variables set "by Jenkins" are assumed to come from this plugin:
    * https://wiki.jenkins-ci.org/display/JENKINS/GitHub+pull+request+builder+plugin
    */

  // settings
  val PullIdEnvVarName = "ghprbPullId" // Set by "GitHub pull request builder plugin"

  val TargetBranchEnvVarName = "PR_TARGET_BRANCH"
  val TargetBranchJenkinsEnvVarName = "ghprbTargetBranch"
  val SourceBranchEnvVarName = "PR_SOURCE_BRANCH"
  val SourcePullIdJenkinsEnvVarName = "ghprbPullId" // used to obtain branch name in form of "pullreq/17397"

  val sourceBranch = settingKey[String]("Branch containing the changes of this PR")
  val targetBranch = settingKey[String]("Target branch of this PR, defaults to `master`")

  // asking github comments if this PR should be PLS BUILD ALL
  val gitHubEnforcedBuildAll = taskKey[Option[BuildMode]]("Checks via GitHub API if comments included the PLS BUILD ALL keyword")
  val buildAllKeyword = taskKey[Regex]("Magic phrase to be used to trigger building of the entire project instead of analysing dependencies")

  // determining touched dirs and projects
  val changedDirectories = taskKey[immutable.Set[String]]("List of touched modules in this PR branch")
  val validatePRprojectBuildMode = taskKey[BuildMode]("Determines what will run when this project is affected by the PR and should be rebuilt")

  // running validation
  val validatePullRequest = taskKey[Unit]("Validate pull request and report aggregated results")
  val executePullRequestValidation = taskKey[Seq[KeyValue[Result[Any]]]]("Run pull request per project")
  val additionalTasks = settingKey[Seq[TaskKey[_]]]("Additional tasks for pull request validation")

  // The set of (top-level) files or directories to watch for build changes.
  val BuildFilesAndDirectories = Set("project", "build.sbt")

  def changedDirectoryIsDependency(changedDirs: Set[String], name: String, graphsToTest: Seq[(Configuration, ModuleGraph)])(log: Logger): Boolean = {
    val dirsOrExperimental = changedDirs.flatMap(dir => Set(dir, s"$dir-experimental"))
    graphsToTest exists { case (ivyScope, deps) =>
      log.debug(s"Analysing [$ivyScope] scoped dependencies...")

      deps.nodes.foreach { m => log.debug(" -> " + m.id) }

      // if this project depends on a modified module, we must test it
      deps.nodes.exists { m =>
        // match just by name, we'd rather include too much than too little
        val dependsOnModule = dirsOrExperimental.find(m.id.name contains _)
        val depends = dependsOnModule.isDefined
        if (depends) log.info(s"Project [$name] must be verified, because depends on [${dependsOnModule.get}]")
        depends
      }
    }
  }

  def localTargetBranch: Option[String] = System.getenv.asScala.get("PR_TARGET_BRANCH")
  def jenkinsTargetBranch: Option[String] = System.getenv.asScala.get("ghprbTargetBranch")
  def runningOnJenkins: Boolean = jenkinsTargetBranch.isDefined
  def runningLocally: Boolean = !runningOnJenkins

  override lazy val buildSettings = Seq(
    sourceBranch in Global in ValidatePR := {
      sys.env.get(SourceBranchEnvVarName) orElse
        sys.env.get(SourcePullIdJenkinsEnvVarName).map("pullreq/" + _) getOrElse // Set by "GitHub pull request builder plugin"
        "HEAD"
    },

    targetBranch in Global in ValidatePR := {
      (localTargetBranch, jenkinsTargetBranch) match {
        case (Some(local), _)     => local // local override
        case (None, Some(branch)) => s"origin/$branch" // usually would be "master" or "release-2.3" etc
        case (None, None)         => "origin/master" // defaulting to diffing with "master"
      }
    },

    buildAllKeyword in Global in ValidatePR := """PLS BUILD ALL""".r,

    gitHubEnforcedBuildAll in Global in ValidatePR := {
      val log = streams.value.log
      val buildAllMagicPhrase = (buildAllKeyword in ValidatePR).value
      sys.env.get(PullIdEnvVarName).map(_.toInt) flatMap { prId =>
        log.info("Checking GitHub comments for PR validation options...")

        try {
          import scala.collection.JavaConverters._
          val gh = GitHubBuilder.fromEnvironment().withOAuthToken(GitHub.envTokenOrThrow).build()
          val comments = gh.getRepository("akka/akka-http").getIssue(prId).getComments.asScala

          def triggersBuildAll(c: GHIssueComment): Boolean = buildAllMagicPhrase.findFirstIn(c.getBody).isDefined
          comments collectFirst { case c if triggersBuildAll(c) =>
            BuildCommentForcedAll(buildAllMagicPhrase.toString(), c)
          }
        } catch {
          case ex: Exception =>
            log.warn("Unable to reach GitHub! Exception was: " + ex.getMessage)
            None
        }
      }
    },

    changedDirectories in Global in ValidatePR := {
      val log = streams.value.log
      val prId = (sourceBranch in ValidatePR).value
      val target = (targetBranch in ValidatePR).value

      log.info(s"Diffing [$prId] to determine changed modules in PR...")
      val diffOutput = s"git diff $target --name-only".!!.split("\n")
      val diffedModuleNames =
        diffOutput
          .map(l => l.trim)
          .filter(l =>
            l.startsWith("akka-") ||
              l.startsWith("docs") ||
              BuildFilesAndDirectories.exists(l startsWith)
          )
          .map(l => l.takeWhile(_ != '/'))
          .toSet

      val dirtyModuleNames: Set[String] =
        if (runningOnJenkins) Set.empty
        else {
          val statusOutput = s"git status --short".!!.split("\n")
          val dirtyDirectories = statusOutput
            .map(l => l.trim.dropWhile(_ != ' ').drop(1))
            .map(_.takeWhile(_ != '/'))
            .filter(dir => dir.startsWith("akka-") || dir.startsWith("docs") || BuildFilesAndDirectories.contains(dir))
            .toSet
          log.info("Detected uncommitted changes in directories (including in dependency analysis): " + dirtyDirectories.mkString("[", ",", "]"))
          dirtyDirectories
        }

      val allModuleNames = dirtyModuleNames ++ diffedModuleNames
      log.info("Detected changes in directories: " + allModuleNames.mkString("[", ", ", "]"))
      allModuleNames
    }
  )

  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),

    validatePRprojectBuildMode in ValidatePR := {
      val log = streams.value.log
      log.debug(s"Analysing project (for inclusion in PR validation): [${name.value}]")
      val changedDirs = (changedDirectories in ValidatePR).value
      val githubCommandEnforcedBuildAll = (gitHubEnforcedBuildAll in ValidatePR).value

      val thisProjectId = CrossVersion(scalaVersion.value, scalaBinaryVersion.value)(projectID.value)

      def graphFor(updateReport: UpdateReport, config: Configuration): (Configuration, ModuleGraph) =
        config -> SbtUpdateReport.fromConfigurationReport(updateReport.configuration(config).get, thisProjectId)

      def isDependency: Boolean = {
        changedDirectoryIsDependency(
          changedDirs,
          name.value,
          Seq(
            graphFor((update in Compile).value, Compile),
            graphFor((update in Test).value, Test),
            graphFor((update in Runtime).value, Runtime),
            graphFor((update in Provided).value, Provided),
            graphFor((update in Optional).value, Optional)))(log)
      }

      if (githubCommandEnforcedBuildAll.isDefined)
        githubCommandEnforcedBuildAll.get
      else if (changedDirs exists (BuildFilesAndDirectories contains))
        BuildProjectChangedQuick
      else if (isDependency)
        BuildQuick
      else
        BuildSkip
    },

    additionalTasks in ValidatePR := Seq.empty,

    executePullRequestValidation := Def.taskDyn {
      val log = streams.value.log
      val buildMode = (validatePRprojectBuildMode in ValidatePR).value

      buildMode.log(name.value, log)

      val validationTasks: Seq[TaskKey[Any]] = (buildMode.task.toSeq ++ (buildMode match {
        case BuildSkip => Seq.empty // do not run the additional task if project is skipped during pr validation
        case _ => (additionalTasks in ValidatePR).value
      })).asInstanceOf[Seq[TaskKey[Any]]]

      val thisProject = Def.resolvedScoped.value.scope.project.toOption.get

      // Create a task for every validation task key and
      // then zip all of the tasks together discarding outputs.
      // Task failures are propagated as normal.
      val zero: Def.Initialize[Seq[Task[KeyValue[Result[Any]]]]] = Def.setting { Seq(task(()).result.map(res => KeyValue(null, res)))}
      validationTasks.map(taskKey => Def.task { taskKey.value }.result.map(res => KeyValue(taskKey in thisProject, res)) ).foldLeft(zero) { (acc, current) =>
        acc.zipWith(current) { case (taskSeq, task) =>
          taskSeq :+ task.asInstanceOf[Task[KeyValue[Result[Any]]]]
        }
      } apply { tasks: Seq[Task[KeyValue[Result[Any]]]] =>
        tasks.join
      }
    }.value
  )
}

object AggregatePRValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = noTrigger

  override lazy val projectSettings = Seq(
    validatePullRequest := {
      val log = streams.value.log

      val extracted = Project extract state.value
      val keys = Aggregation.aggregatedKeys(validatePullRequest in extracted.currentRef, extracted.structure.extra, ScopeMask())

      log.info(s"AGGREGATE KEYS for ${extracted.currentRef}")
      keys.foreach(x => log.info(x.toString))
      log.info("END")

      def timedRun[T](s: State, ts: Seq[sbt.internal.Aggregation.KeyValue[Task[T]]], extra: DummyTaskMap): Complete[Result[T]] = {
        import EvaluateTask._
        import std.TaskExtra._

        val extracted = Project extract s
        import extracted.structure
        val toRun: Seq[Task[KeyValue[Result[T]]]] = ts map { case KeyValue(k, t) => t.result.map(v => KeyValue(k, v)) }
        val joined = Task[Seq[KeyValue[Result[T]]]](Info(), new Join(toRun, { (s: Seq[Result[KeyValue[Result[T]]]]) =>
          val extracted = s.map {
            case _: Inc => throw new IllegalStateException("Should not happen") // should not happen because of `result` above
            case Value(kv) => kv
          }
          Right(extracted)
        }))

        val roots = ts map { case KeyValue(k, _) => k }
        val config = extractedTaskConfig(extracted, structure, s)

        val start = System.currentTimeMillis
        val (newS, result: Result[Seq[KeyValue[Result[T]]]]) = withStreams(structure, s) { str =>
          val transform = nodeView(s, str, roots, extra)
          runTask(joined, s, str, structure.index.triggers, config)(transform)
        }
        val stop = System.currentTimeMillis
        Complete(start, stop, result, newS)
      }

      def runTasks[T](s: State, structure: BuildStructure, ts: Seq[sbt.internal.Aggregation.KeyValue[Task[T]]], extra: DummyTaskMap, show: ShowConfig)(implicit display: Show[ScopedKey[_]]): (State, Result[Seq[KeyValue[Result[T]]]])  = {
        val complete = timedRun[T](s, ts, extra)
        sbt.access.AggregationShowRun(complete, show)
        val newState =
          complete.results match {
            case Inc(i)   => complete.state.handleError(i)
            case Value(_) => complete.state
          }

        (newState, complete.results)
      }
      def resolve[T](key: ScopedKey[T]): ScopedKey[T] =
        Project.mapScope(Scope.resolveScope(GlobalScope, extracted.currentRef.build, extracted.rootProject))(key.scopedKey)

      def runAggregated[T](key: TaskKey[T], state: State): (State, Result[Seq[KeyValue[Result[T]]]]) = {
        val rkey = resolve(key.scopedKey)
        val keys = Aggregation.aggregate(rkey, ScopeMask(), extracted.structure.extra)
        val tasks = Act.keyValues(extracted.structure)(keys)
        log.info(s"Tasks to aggregate are: $keys $tasks")

        runTasks[T](state, extracted.structure, tasks, DummyTaskMap(Nil), show = Aggregation.defaultShow(state, false))(extracted.showKey)
      }

      val (newState, result) = runAggregated(executePullRequestValidation in extracted.currentRef, state.value)

      val allResults = result.toEither.right.get.flatMap(_.value.toEither.right.get).filterNot(_.key == null).sortBy(_.key.scope.project.toString)

      val onlyTestResults: Seq[KeyValue[Tests.Output]] = allResults collect {
        case KeyValue(key, Value(o: Tests.Output)) => KeyValue(key, o)
      }

      val failedTasks: Seq[KeyValue[Inc]] = allResults collect {
        case KeyValue(key, i: Inc) => KeyValue(key, i)
      }

      val mimaFailures: Seq[KeyValue[MimaResult]] = allResults collect {
        case KeyValue(key, Value(p: Problems)) => KeyValue(key, p)
      }

      val outputFile = new File(target.value, "pr-validation-report.txt")
      val fw = new PrintWriter(new FileWriter(outputFile))
      def write(msg: String): Unit = {
        log.info(msg)
        fw.println(msg)
      }

      val testLogger = LogExchange.logger("testLogger")
      val appender = MainAppender.defaultBacked(useFormat = false)(fw)
      LogExchange.bindLoggerAppenders("testLogger", appender -> Level.Info :: Nil)

      log.info("")
      write("")
      write("# Pull request validation report")
      write("")

      def showKey(key: ScopedKey[_]): String = Project.showContextKey2(extracted.session).show(key)

      def totalCount(suiteResult: SuiteResult): Int = {
        import suiteResult._
        passedCount + failureCount + errorCount + skippedCount + ignoredCount + canceledCount + pendingCount
      }
      def hasExecutedTests(suiteResult: SuiteResult): Boolean = totalCount(suiteResult) > 0
      def hasTests(result: Tests.Output): Boolean = result.events.exists(e => hasExecutedTests(e._2))
      def printTestResults(result: KeyValue[Tests.Output]): Unit = {
        write(s"Test result for '${showKey(result.key)}'")
        write("")
        write("```")

        def safeLogTestResults(logger: Logger): Unit =
          Try(TestResultLogger.Default.run(logger, result.value, showKey(result.key)))

        // HACK: there is no logger which would both log to our file and the console, so we log twice
        safeLogTestResults(log)
        safeLogTestResults(testLogger)

        // there seems to be some async logging going on, so let's wait for a while to be sure the appender has flushed
        Thread.sleep(100)

        write("```")
        write("")
      }

      val (passed0, failed) = onlyTestResults.partition(_.value.overall == TestResult.Passed)
      val passed = passed0.filter(t => hasTests(t.value))

      if (failed.nonEmpty) {
        write("## Failed Test Suites")
        write("")
        failed.foreach(printTestResults)
        write("")
      }

      if (mimaFailures.nonEmpty) {
        write("## Mima Failures")
        write("")
        write("```")
        mimaFailures.foreach {
          case KeyValue(key, Problems(desc)) =>
            write(s"Problems for ${key.scope.project.toOption.get.asInstanceOf[ProjectRef].project}:\n$desc")
            write("")
          case KeyValue(_, NoErrors) =>
        }
        write("```")
        write("")
      }

      if (failedTasks.nonEmpty) {
        write("## Other Failed tasks")
        write("")
        failedTasks foreach { case KeyValue(key, Inc(inc: Incomplete)) =>
          def parseIncomplete(inc: Incomplete): String =
            "an underlying problem during task execution:\n" +
            Incomplete.linearize(inc).filter(x => x.message.isDefined || x.directCause.isDefined)
              .map { case i @ Incomplete(node, tpe, message, causes, directCause) =>
                def nodeName: String = node match {
                  case Some(key: ScopedKey[_]) => showKey(key)
                  case Some(t: Task[_]) =>
                    t.info.name
                      .orElse(t.info.attributes.get(taskDefinitionKey).map(showKey))
                      .getOrElse(t.info.toString)
                  case Some(x) => s"<$x>"
                  case None => "<unknown>"
                }

                s"  $nodeName: ${message.orElse(directCause.map(_.toString)).getOrElse(s"<unknown: ($i)>")}"
              }.mkString("\n")

          val problem = inc.directCause.map(_.toString).getOrElse(parseIncomplete(inc))

          write(s"${showKey(key)} failed because of $problem")
          write("")
        }
      }

      /*if (passed.nonEmpty) {
        write("+ Successful Test Suites")
        write("")

        passed.foreach(printTestResults)
        write("")
      }*/

      write("")

      fw.close()
      log.info(s"Wrote PR validation report to ${outputFile.getAbsolutePath}")
      //write(s"Overall result was: $result")

      if (failed.nonEmpty) throw new RuntimeException(s"Pull request validation failed! Tests failed: $failed")
      else if (mimaFailures.nonEmpty) throw new RuntimeException(s"Pull request validation failed! Mima failures: $mimaFailures")
      else if (failedTasks.nonEmpty) throw new RuntimeException(s"Pull request validation failed! Failed tasks: $failedTasks")
      ()
    }
  )
}

/**
  * This auto plugin adds MiMa binary issue reporting to validatePullRequest task,
  * when a project has MimaPlugin auto plugin enabled.
  */
object MimaWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._
  import com.typesafe.tools.mima.plugin._
  import MimaKeys._

  sealed trait MimaResult
  case object NoErrors extends MimaResult
  case class Problems(problemDescription: String) extends MimaResult

  val mimaResult = taskKey[MimaResult]("Aggregates mima result strings")

  override def trigger = allRequirements
  override def requires = ValidatePullRequest && MimaPlugin
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += mimaResult,

    mimaResult := {
      import com.typesafe.tools.mima.core
      def reportModuleErrors(module: ModuleID,
                             backward: List[core.Problem],
                             forward: List[core.Problem],
                             filters: Seq[core.ProblemFilter],
                             backwardFilters: Map[String, Seq[core.ProblemFilter]],
                             forwardFilters: Map[String, Seq[core.ProblemFilter]],
                             log: String => Unit, projectName: String): Boolean = {
        // filters * found is n-squared, it's fixable in principle by special-casing known
        // filter types or something, not worth it most likely...

        // version string "x.y.z" is converted to an Int tuple (x, y, z) for comparison
        val versionOrdering = Ordering[(Int, Int, Int)].on { version: String =>
          val ModuleVersion = """(\d+)\.(\d+)\.(.*)""".r
          val ModuleVersion(epoch, major, minor) = version
          val toNumeric = (revision: String) => Try(revision.filter(_.isDigit).toInt).getOrElse(0)
          (toNumeric(epoch), toNumeric(major), toNumeric(minor))
        }

        def isReported(module: ModuleID, verionedFilters: Map[String, Seq[core.ProblemFilter]])(problem: core.Problem) = (verionedFilters.collect {
          // get all filters that apply to given module version or any version after it
          case f @ (version, filters) if versionOrdering.gteq(version, module.revision) => filters
        }.flatten ++ filters).forall { f =>
          if (f(problem)) {
            true
          } else {
            //log(projectName + ": filtered out: " + problem.description + "\n  filtered by: " + f)
            false
          }
        }

        val backErrors = backward filter isReported(module, backwardFilters)
        val forwErrors = forward filter isReported(module, forwardFilters)

        val filteredCount = backward.size + forward.size - backErrors.size - forwErrors.size
        val filteredNote = if (filteredCount > 0) " (filtered " + filteredCount + ")" else ""

        // TODO - Line wrapping an other magikz
        def prettyPrint(p: core.Problem, affected: String): String = {
          " * " + p.description(affected) + p.howToFilter.map("\n   filter with: " + _).getOrElse("")
        }

        log(s"$projectName: found ${backErrors.size+forwErrors.size} potential binary incompatibilities while checking against $module $filteredNote")
        ((backErrors map {p: core.Problem => prettyPrint(p, "current")}) ++
          (forwErrors map {p: core.Problem => prettyPrint(p, "other")})) foreach { p =>
          log(p)
        }
        backErrors.nonEmpty || forwErrors.nonEmpty
      }

      def withLogger[T](f: (String => Unit) => T): (String, T) = {
        val stringWriter = new StringWriter()
        val printWriter = new PrintWriter(stringWriter)

        val result = f(printWriter.println)

        printWriter.close()
        stringWriter.close()
        (stringWriter.toString, result)
      }


      val allResults =
        mimaPreviousClassfiles.value.toSeq.map {
          case (moduleId, file) =>
            val problems = SbtMima.runMima(
              file,
              mimaCurrentClassfiles.value,
              (fullClasspath in mimaFindBinaryIssues).value,
              mimaCheckDirection.value,
              new SbtLogger(streams.value)
            )

            val binary = mimaBinaryIssueFilters.value
            val backward = mimaBackwardIssueFilters.value
            val forward = mimaForwardIssueFilters.value

            withLogger { logger =>
              reportModuleErrors(
                moduleId,
                problems._1, problems._2,
                binary,
                backward,
                forward,
                logger,
                name.value)
            }
        }

      val noErrors = allResults.forall(!_._2)
      if (noErrors) NoErrors
      else {
        val erroneous = allResults.filter(_._2)
        Problems(erroneous.map(_._1).mkString("\n"))
      }
    }
  )
}

/**
  * This auto plugin adds UniDoc unification to validatePullRequest task.
  */
object UniDocWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += unidoc in Compile
  )
}
