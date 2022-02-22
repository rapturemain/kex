package org.jetbrains.research.kex.sbst

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.asm.analysis.testgen.DescriptorChecker
import org.jetbrains.research.kex.asm.analysis.testgen.MethodChecker
import org.jetbrains.research.kex.asm.analysis.testgen.RandomChecker
import org.jetbrains.research.kex.asm.manager.ClassInstantiationDetector
import org.jetbrains.research.kex.asm.manager.CoverageCounter
import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kex.asm.transform.RuntimeTraceCollector
import org.jetbrains.research.kex.asm.transform.SystemExitTransformer
import org.jetbrains.research.kex.asm.util.ClassWriter
import org.jetbrains.research.kex.asm.util.Visibility
import org.jetbrains.research.kex.config.FileConfig
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.random.easyrandom.EasyRandomDriver
import org.jetbrains.research.kex.reanimator.collector.MethodFieldAccessCollector
import org.jetbrains.research.kex.reanimator.collector.SetterCollector
import org.jetbrains.research.kex.reanimator.descriptor.DescriptorStatistics
import org.jetbrains.research.kex.trace.`object`.ActionTrace
import org.jetbrains.research.kex.trace.`object`.ObjectTraceManager
import org.jetbrains.research.kex.util.getKexRuntime
import org.jetbrains.research.kex.util.getRuntime
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.KfgConfig
import org.jetbrains.research.kfg.Package
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.container.Container
import org.jetbrains.research.kfg.container.asContainer
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.util.Flags
import org.jetbrains.research.kfg.visitor.executePipeline
import org.jetbrains.research.kthelper.logging.log
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.*

val Container.urls get() = (this.classLoader as? URLClassLoader)?.urLs ?: arrayOf()

@ExperimentalSerializationApi
@InternalSerializationApi
class KexTool : Tool {
    val configFile = "kex.ini"
    lateinit var containers: List<Container>
    lateinit var containerClassLoader: URLClassLoader
    lateinit var context: ExecutionContext

    lateinit var pkg: Package
    var klass: Class? = null
        private set
    val classPath = System.getProperty("java.class.path")

    val traceManager = ObjectTraceManager()
    lateinit var cm: CoverageCounter<ActionTrace>
    lateinit var psa: PredicateStateAnalysis
    val visibility = Visibility.PUBLIC


    private fun updateClassPath(loader: URLClassLoader) {
        val urlClassPath = loader.urLs.joinToString(separator = ":") { "${it.path}." }
        System.setProperty("java.class.path", "$classPath:$urlClassPath")
    }

    private fun clearClassPath() {
        System.setProperty("java.class.path", classPath)
    }

    init {
        kexConfig.initialize(RuntimeConfig, FileConfig(configFile))
    }

    override fun getExtraClassPath(): List<File> = emptyList()

    private fun prepareInstrumentedClasspath(containers: List<Container>, target: Package, path: Path) {
        val cm = ClassManager(KfgConfig(flags = Flags.readAll, failOnError = false, verifyIR = false))
        cm.initialize(*containers.toTypedArray())
        val klassPath = containers.map { it.path }
        val context = ExecutionContext(
            cm,
            target,
            containerClassLoader,
            EasyRandomDriver(),
            klassPath
        )

        containers.forEach { it.unpack(cm, path, true) }

        executePipeline(cm, target) {
            +SystemExitTransformer(cm)
            +RuntimeTraceCollector(context.cm)
            +ClassWriter(context, path)
        }
        log.debug("Executed instrumentation pipeline")
    }


    override fun initialize(src: File, bin: File, classPath: List<File>) {
        val targetContainer = bin.asContainer()!!
        pkg = targetContainer.pkg
        containers = classPath.mapNotNull { it.asContainer() }

        val jarClassLoader = URLClassLoader(containers.flatMap { it.urls.toList() }.toTypedArray())
        log.debug("Initialized containers: ${containers.joinToString { it.name }}")

        val instrumentedDirName = kexConfig.getStringValue("output", "instrumentedDir", "instrumented")
        val instrumentedCodeDir = kexConfig.getPathValue("kex", "outputDir")!!.resolve(instrumentedDirName).toAbsolutePath()
        containerClassLoader = URLClassLoader(containers.map { it.path.toUri().toURL() }.toTypedArray())

        prepareInstrumentedClasspath(containers, pkg, instrumentedCodeDir)
        containerClassLoader = URLClassLoader(arrayOf(instrumentedCodeDir.toUri().toURL()))

        val classManager = ClassManager(KfgConfig(flags = Flags.readAll, failOnError = false))
        classManager.initialize(jarClassLoader, *containers.toTypedArray(), getRuntime()!!, getKexRuntime()!!)
        log.debug("Initialized class managers")

        // write all classes to output directory, so they will be seen by ClassLoader
        log.debug("Unpacked jar files")

        val klassPath = containers.map { it.path }
        updateClassPath(containerClassLoader)
        val randomDriver = EasyRandomDriver()
        context = ExecutionContext(classManager, pkg, containerClassLoader, randomDriver, klassPath)

        psa = PredicateStateAnalysis(context.cm)
        cm = CoverageCounter(context.cm, traceManager)

        updateClassPath(context.loader as URLClassLoader)

        executePipeline(context.cm, pkg) {
            +RandomChecker(context, psa, visibility, traceManager)
            +LoopSimplifier(context.cm)
            +LoopDeroller(context.cm)
            +psa
            +MethodFieldAccessCollector(context, psa)
            +SetterCollector(context)
            +ClassInstantiationDetector(context.cm, visibility)
        }
        log.debug("Executed analysis pipeline")
    }

    override fun run(className: String, timeBudget: Long) {
        val canonicalName = className.replace('.', '/')
        val klass = context.cm[canonicalName]
        log.debug("Running on klass $klass")
        val useApiGeneration = kexConfig.getBooleanValue("apiGeneration", "enabled", true)
        executePipeline(context.cm, klass) {
            +when {
                useApiGeneration -> DescriptorChecker(context, traceManager, psa, timeBudget * 1000)
                else -> MethodChecker(context, traceManager, psa, timeBudget * 1000)
            }
        }
        log.debug("Analyzed klass $klass")
    }

    override fun finalize() {
        clearClassPath()
    }
}