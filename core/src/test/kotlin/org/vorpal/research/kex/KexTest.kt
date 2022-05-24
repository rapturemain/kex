package org.vorpal.research.kex

import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.transform.LoopDeroller
import org.vorpal.research.kex.config.FileConfig
import org.vorpal.research.kex.config.RuntimeConfig
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.util.getIntrinsics
import org.vorpal.research.kex.util.getKexRuntime
import org.vorpal.research.kex.util.getRuntime
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.KfgConfig
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.container.Container
import org.vorpal.research.kfg.container.asContainer
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.util.Flags
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kfg.visitor.pipelineStub
import org.vorpal.research.kfg.visitor.schedule
import java.nio.file.Paths

abstract class KexTest {
    val packageName = "org.vorpal.research.kex.test"
    val `package` = Package.parse("$packageName.*")
    val jarPath: String
    val jar: Container
    val cm: ClassManager
    val loader: ClassLoader

    init {
        val rootDir = System.getProperty("root.dir")
        val version = System.getProperty("project.version")
        kexConfig.initialize(RuntimeConfig, FileConfig("$rootDir/kex-test.ini"))
        kexConfig.initLog("kex-test.log")

        jarPath = "$rootDir/kex-test/target/kex-test-$version-jar-with-dependencies.jar"
        jar = Paths.get(jarPath).asContainer(`package`)!!

        val jars = listOfNotNull(jar, getRuntime(), getKexRuntime(), getIntrinsics())
        loader = jars.first().classLoader
        cm = ClassManager(KfgConfig(flags = Flags.readAll, failOnError = false))
        cm.initialize(*jars.toTypedArray())
    }

    protected fun getPSA(method: Method): PredicateStateAnalysis {
        if (method.hasLoops) {
            executePipeline(cm, listOf(method)) {
                schedule<LoopDeroller>()
            }
        }

        val psa = PredicateStateAnalysis(cm, pipelineStub())
        psa.visit(method)
        return psa
    }
}