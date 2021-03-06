// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop

import java.io.File
import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import monix.eval.Task
import sbt.internal.inc.{Analysis, CompileConfiguration, CompileOutput, Incremental, LookupImpl, MiniSetupUtil, MixedAnalyzingCompiler}
import xsbti.{AnalysisCallback, Logger, Reporter}
import sbt.internal.inc.JavaInterfaceUtil.{EnrichOptional, EnrichSbtTuple}
import sbt.internal.inc.bloop.internal.{BloopHighLevelCompiler, BloopIncremental}
import sbt.util.InterfaceUtil
import xsbti.compile._

object BloopZincCompiler {

  /**
   * Performs an incremental compilation based on [[xsbti.compile.Inputs]].
   *
   * This is a Scala implementation of [[xsbti.compile.IncrementalCompiler]],
   * check the docs for more information on the specification of this method.
   *
   * @param in An instance of [[xsbti.compile.Inputs]] that collect all the
   *           inputs required to run the compiler (from sources and classpath,
   *           to compilation order, previous results, current setup, etc).
   * @param compileMode The compiler mode in which compilation needs to run.
   * @param logger An instance of [[xsbti.Logger]] to log Zinc output.
   *
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  def compile(
      in: Inputs,
      compileMode: CompileMode,
      logger: Logger
  ): Task[CompileResult] = {
    val config = in.options()
    val setup = in.setup()
    import config._
    import setup._
    val compilers = in.compilers
    val javacChosen = compilers.javaTools.javac
    val scalac = compilers.scalac
    val extraOptions = extra.toList.map(_.toScalaTuple)
    compileIncrementally(
      scalac,
      javacChosen,
      sources,
      classpath,
      picklepath,
      CompileOutput(classesDirectory),
      cache,
      progress().toOption,
      scalacOptions,
      javacOptions,
      classpathOptions,
      in.previousResult.analysis.toOption,
      in.previousResult.setup.toOption,
      perClasspathEntryLookup,
      reporter,
      order,
      skip,
      incrementalCompilerOptions,
      extraOptions,
      picklePromise,
      compileMode
    )(logger)
  }

  def compileIncrementally(
      scalaCompiler: xsbti.compile.ScalaCompiler,
      javaCompiler: xsbti.compile.JavaCompiler,
      sources: Array[File],
      classpath: Seq[File],
      picklepath: Seq[URI],
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      scalaOptions: Seq[String] = Nil,
      javaOptions: Seq[String] = Nil,
      classpathOptions: ClasspathOptions,
      previousAnalysis: Option[CompileAnalysis],
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: Reporter,
      compileOrder: CompileOrder = CompileOrder.Mixed,
      skip: Boolean = false,
      incrementalOptions: IncOptions,
      extra: List[(String, String)],
      picklePromise: CompletableFuture[Optional[URI]],
      compileMode: CompileMode
  )(implicit logger: Logger): Task[CompileResult] = {
    val prev = previousAnalysis match {
      case Some(previous) => previous
      case None => Analysis.empty
    }

    // format: off
    val configTask = configureAnalyzingCompiler(scalaCompiler, javaCompiler, sources.toSeq, classpath, picklepath, output, cache, progress, scalaOptions, javaOptions, classpathOptions, prev, previousSetup, perClasspathEntryLookup, reporter, compileOrder, skip, incrementalOptions, extra)
    // format: on
    configTask.flatMap { config =>
      if (skip) Task.now(CompileResult.of(prev, config.currentSetup, false))
      else {
        val setOfSources = sources.toSet
        val compiler = BloopHighLevelCompiler(config, logger)
        val lookup = new LookupImpl(config, previousSetup)
        val analysis = invalidateAnalysisFromSetup(config.currentSetup, previousSetup, incrementalOptions.ignoredScalacOptions(), setOfSources, prev)

        // Scala needs the explicit type signature to infer the function type arguments
        val compile: (Set[File], DependencyChanges, AnalysisCallback, ClassFileManager) => Task[Unit] = compiler.compile(_, _, _, _, compileMode)
        BloopIncremental.compile(setOfSources, lookup, compile, analysis, output, logger, config.incOptions, picklePromise).map {
          case (changed, analysis) => CompileResult.of(analysis, config.currentSetup, changed)
        }
      }
    }
  }

  /**
    * Invalidates the analysis file to be used depending if the setup is the same or not.
    *
    * This logic used to be in `compileInternal` in
    * [[sbt.internal.inc.IncrementalCompilerImpl.compileInternal]], but we've moved it here
    * to reason more accurately about it.
    *
    * @param setup The current setup that we use to compile.
    * @param previousSetup The previous setup, [[None]] if first compilation.
    * @param ignoredScalacOptions The scalac options we should ignore for equivalence.
    * @param sources The sources with which we prune the analysis file.
    * @param previousAnalysis The analysis from the previous compilation.
    * @return An analysis, either empty if the setups are not the same, pruned or the previous analysis.
    */
  def invalidateAnalysisFromSetup(
      setup: MiniSetup,
      previousSetup: Option[MiniSetup],
      ignoredScalacOptions: Array[String],
      sources: Set[File],
      previousAnalysis: CompileAnalysis
  ): CompileAnalysis = {
    // Need wildcard import b/c otherwise `scala.math.Equiv.universal` is used and returns false
    import MiniSetupUtil._
    val equiv = equivCompileSetup(equivOpts0(equivScalacOptions(ignoredScalacOptions)))
    previousSetup match {
      case Some(previous) => // Return an empty analysis if values of extra have changed
        if (equiv.equiv(previous, setup)) previousAnalysis
        else if (!equivPairs.equiv(previous.extra, setup.extra)) Analysis.empty
        else Incremental.prune(sources, previousAnalysis)
      case None => Incremental.prune(sources, previousAnalysis)
    }
  }

  def configureAnalyzingCompiler(
      scalac: xsbti.compile.ScalaCompiler,
      javac: xsbti.compile.JavaCompiler,
      sources: Seq[File],
      classpath: Seq[File],
      picklepath: Seq[URI],
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      options: Seq[String] = Nil,
      javacOptions: Seq[String] = Nil,
      classpathOptions: ClasspathOptions,
      previousAnalysis: CompileAnalysis,
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: Reporter,
      compileOrder: CompileOrder = CompileOrder.Mixed,
      skip: Boolean = false,
      incrementalCompilerOptions: IncOptions,
      extra: List[(String, String)]
  ): Task[CompileConfiguration] = {
    val lookup = incrementalCompilerOptions.externalHooks().getExternalLookup
    ClasspathHashing.hash(classpath).map { classpathHashes =>
      val compileSetup = MiniSetup.of(
        output,
        MiniOptions.of(
          classpathHashes.toArray,
          options.toArray,
          javacOptions.toArray
        ),
        scalac.scalaInstance.actualVersion,
        compileOrder,
        incrementalCompilerOptions.storeApis(),
        (extra map InterfaceUtil.t2).toArray
      )

      MixedAnalyzingCompiler.config(
        sources,
        classpath,
        classpathOptions,
        picklepath,
        compileSetup,
        progress,
        previousAnalysis,
        previousSetup,
        perClasspathEntryLookup,
        scalac,
        javac,
        reporter,
        skip,
        cache,
        incrementalCompilerOptions
      )
    }
  }
}
