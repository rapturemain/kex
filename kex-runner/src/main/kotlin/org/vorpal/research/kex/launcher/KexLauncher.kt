package org.vorpal.research.kex.launcher

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.manager.CoverageCounter
import org.vorpal.research.kex.asm.manager.MethodWrapperInitializer
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.transform.BranchAdapter
import org.vorpal.research.kex.asm.transform.LoopDeroller
import org.vorpal.research.kex.asm.transform.RuntimeTraceCollector
import org.vorpal.research.kex.asm.transform.SystemExitTransformer
import org.vorpal.research.kex.asm.util.ClassWriter
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.random.easyrandom.EasyRandomDriver
import org.vorpal.research.kex.reanimator.collector.MethodFieldAccessCollector
import org.vorpal.research.kex.reanimator.collector.SetterCollector
import org.vorpal.research.kex.trace.AbstractTrace
import org.vorpal.research.kex.trace.TraceManager
import org.vorpal.research.kex.util.getIntrinsics
import org.vorpal.research.kex.util.getKexRuntime
import org.vorpal.research.kex.util.getPathSeparator
import org.vorpal.research.kex.util.getRuntime
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.KfgConfig
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.container.Container
import org.vorpal.research.kfg.container.asContainer
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.parseStringToType
import org.vorpal.research.kfg.util.Flags
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kfg.visitor.schedule
import org.vorpal.research.kthelper.logging.log
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

sealed class AnalysisLevel {
    abstract val pkg: Package
    abstract val levelName: String
}

data class PackageLevel(override val pkg: Package) : AnalysisLevel() {
    override val levelName: String
        get() = "package"
    override fun toString() = "package $pkg"
}

data class ClassLevel(val klass: Class) : AnalysisLevel() {
    override val levelName: String
        get() = "class"
    override val pkg = klass.pkg
    override fun toString() = "class $klass"
}

data class MethodLevel(val method: Method) : AnalysisLevel() {
    override val levelName: String
        get() = "method"
    override val pkg = method.klass.pkg
    override fun toString() = "method $method"
}

abstract class KexLauncher(classPaths: List<String>, targetName: String) {
    val classPath = System.getProperty("java.class.path")

    val containers: List<Container>
    val containerClassLoader: URLClassLoader
    val context: ExecutionContext
    val analysisLevel: AnalysisLevel
    val visibilityLevel: Visibility

    init {
        val containerPaths = classPaths.map { Paths.get(it).toAbsolutePath() }
        containerClassLoader = URLClassLoader(containerPaths.map { it.toUri().toURL() }.toTypedArray())
        containers = listOfNotNull(*containerPaths.map {
            it.asContainer() ?: run {
                log.error("Can't represent ${it.toAbsolutePath()} as class container")
                exitProcess(1)
            }
        }.toTypedArray(), getKexRuntime())
        val analysisJars = listOfNotNull(*containers.toTypedArray(), getRuntime(), getIntrinsics())

        val instrumentedDirName = kexConfig.getStringValue("output", "instrumentedDir", "instrumented")
        val instrumentedCodeDir = kexConfig.getPathValue("kex", "outputDir")!!.resolve(instrumentedDirName)
        prepareInstrumentedClasspath(analysisJars, Package.defaultPackage, instrumentedCodeDir)

        val cm = ClassManager(
            KfgConfig(
                flags = Flags.readAll,
                useCachingLoopManager = false,
                failOnError = false,
                verifyIR = false,
                checkClasses = false
            )
        )
        cm.initialize(*analysisJars.toTypedArray())

        val packageRegex = """[\w$]+(\.[\w$]+)*\.\*"""
        val klassNameRegex = """(${packageRegex.dropLast(2)})?[\w$]+"""
        val methodNameRegex = """(<init>|<clinit>|(\w+))"""
        val typeRegex = """(void|((byte|char|short|int|long|float|double|$klassNameRegex)(\[\])*))"""
        analysisLevel = when {
            targetName == "${Package.EXPANSION}" -> PackageLevel(Package.defaultPackage)
            targetName.matches(Regex(packageRegex)) -> PackageLevel(Package.parse(targetName))
            targetName.matches(Regex("""$klassNameRegex::$methodNameRegex\((($typeRegex,\s*)*$typeRegex)?\):\s*$typeRegex""")) -> {
                val (klassName, methodFullDesc) = targetName.split("::")
                val (methodName, methodArgs, methodReturn) = methodFullDesc.split("(", "):")
                val klass = cm[klassName.replace('.', '/')]
                val method = klass.getMethod(
                    methodName,
                    parseStringToType(cm.type, methodReturn.trim().replace('.', '/')),
                    *methodArgs.trim().split(""",\s*""".toRegex()).filter { it.isNotBlank() }.map {
                        parseStringToType(cm.type, it.replace('.', '/'))
                    }.toTypedArray()
                )
                MethodLevel(method)
            }
            targetName.matches(Regex(klassNameRegex)) -> {
                val klass = cm[targetName.replace('.', '/')]
                ClassLevel(klass)
            }
            else -> {
                log.error("Could not parse target $targetName")
                exitProcess(1)
            }
        }
        log.debug("Target: $analysisLevel")

        // write all classes to output directory, so they will be seen by ClassLoader
        val classLoader = URLClassLoader(arrayOf(instrumentedCodeDir.toUri().toURL()))

        val klassPath = containers.map { it.path }
        updateClassPath(classLoader)
        val randomDriver = EasyRandomDriver()
        context = ExecutionContext(cm, analysisLevel.pkg, classLoader, randomDriver, klassPath)

        log.debug("Running with class path:\n${containers.joinToString("\n") { it.name }}")
        visibilityLevel = kexConfig.getEnumValue("testGen", "visibility", true, Visibility.PUBLIC)
    }

    abstract fun launch()

    protected open fun createInstrumenter(context: ExecutionContext, pipeline: Pipeline): MethodVisitor = RuntimeTraceCollector(context.cm, pipeline)

    private fun prepareInstrumentedClasspath(containers: List<Container>, target: Package, path: Path) {
        val klassPath = containers.map { it.path }
        for (jar in containers) {
            log.info("Preparing ${jar.path}")
            val cm = ClassManager(
                KfgConfig(
                    flags = Flags.readAll,
                    useCachingLoopManager = false,
                    failOnError = false,
                    verifyIR = false,
                    checkClasses = false
                )
            )
            cm.initialize(jar)
            val context = ExecutionContext(
                cm,
                target,
                containerClassLoader,
                EasyRandomDriver(),
                klassPath
            )

            jar.unpack(cm, path, true)

            executePipeline(cm, target) exec@{
                registerProvider(ExecutionContextProvider(context))

                schedule<SystemExitTransformer>()
                schedule(createInstrumenter(context, this@exec), false)
                schedule(ClassWriter(context.cm, this@exec, path), false)
            }
        }
    }

    protected fun <T : AbstractTrace> createCoverageCounter(cm: ClassManager, pipeline: Pipeline, tm: TraceManager<T>) =
        when (analysisLevel) {
            is MethodLevel -> CoverageCounter(cm, pipeline, tm, setOf(analysisLevel.method))
            is ClassLevel -> CoverageCounter(cm, pipeline, tm, analysisLevel.klass)
            is PackageLevel -> CoverageCounter(cm, pipeline, tm, analysisLevel.pkg)
        }

    protected fun runPipeline(context: ExecutionContext, target: Package, init: Pipeline.() -> Unit) =
        executePipeline(context.cm, target, init)

    protected fun runPipeline(context: ExecutionContext, init: Pipeline.() -> Unit) = when (analysisLevel) {
        is MethodLevel -> executePipeline(context.cm, setOf(analysisLevel.method), init)
        is ClassLevel -> executePipeline(context.cm, analysisLevel.klass, init)
        is PackageLevel -> executePipeline(context.cm, analysisLevel.pkg, init)
    }

    protected open fun preparePackage(
        ctx: ExecutionContext,
        pkg: Package = Package.defaultPackage
    ) = runPipeline(ctx, pkg) {
        schedule<BranchAdapter>()
        schedule<SetterCollector>()
        schedule(ClassInstantiationDetector(ctx.cm, this@runPipeline, visibilityLevel), false)
        registerProvider(ExecutionContextProvider(ctx))
    }

    protected fun updateClassPath(loader: URLClassLoader) {
        val urlClassPath = loader.urLs.joinToString(separator = getPathSeparator()) { "${it.path}." }
        System.setProperty("java.class.path", "$classPath${getPathSeparator()}$urlClassPath")
    }

    protected fun clearClassPath() {
        System.setProperty("java.class.path", classPath)
    }
}