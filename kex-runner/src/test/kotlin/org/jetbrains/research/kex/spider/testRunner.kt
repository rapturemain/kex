package org.jetbrains.research.kex.spider

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import org.jetbrains.research.kex.KexRunnerTest
import org.jetbrains.research.kex.asm.analysis.defect.Defect
import org.jetbrains.research.kex.asm.analysis.defect.DefectManager
import org.jetbrains.research.kex.asm.analysis.libchecker.CallCiteChecker
import org.jetbrains.research.kex.asm.analysis.libchecker.LibslInstrumentator
import org.jetbrains.research.kex.asm.manager.OriginalMapper
import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.asm.transform.BranchAdapter
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kex.asm.util.ClassWriter
import org.jetbrains.research.kex.asm.util.Visibility
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.libsl.LibslDescriptor
import org.jetbrains.research.kex.reanimator.collector.ExternalCtorCollector
import org.jetbrains.research.kex.reanimator.collector.MethodFieldAccessCollector
import org.jetbrains.research.kex.reanimator.collector.SetterCollector
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.visitor.executePipeline
import org.junit.Assert
import java.io.File
import java.net.URLClassLoader


class SpiderTestRunner : KexRunnerTest(initTR = true, initIntrinsics = true) {
    private val tempDir: File = kexConfig.getPathValue("kex", "outputDir")!!.toFile().resolve("spidertests")
    private val referenceDir = File("kex-runner/src/test/kotlin/org/jetbrains/research/kex/spider/reference/")

    init {
        RuntimeConfig.setValue("defect", "outputFile", tempDir.absolutePath + "/defects.json")
    }

    fun testRunner(testName: String) {
        val loader = Thread.currentThread().contextClassLoader
        val lslRes = loader.getResource("org/jetbrains/research/kex/spider/$testName.lsl")?.toURI() ?: error("lsl file not found")
        val lslFile = File(lslRes)

        tempDir.deleteRecursively()
        tempDir.mkdirs()

        updateClassPath(analysisContext.loader as URLClassLoader)

        runPipeline(lslFile.absolutePath)

        val defectsFile = File(tempDir.absolutePath + "/defects.json")
        if (!defectsFile.exists()) {
            error("no defects file was received")
        }
        val actual = DefectManager.defects.sortedBy { it.testFile }
        val sb = StringBuilder(Klaxon().toJsonString(actual))
        val actualJson = (Parser.default().parse(sb) as JsonArray<*>).toJsonString(true)

        val referenceFile = referenceDir.resolve(testName).resolve("defects.json")
        if (!referenceFile.exists()) {
            referenceFile.apply { parentFile.mkdirs() }.createNewFile()
            referenceFile.writeText(actualJson)
            return
        }
        val referenceJson = referenceFile.readText()
        Assert.assertEquals("results are different for test $testName", referenceJson, actualJson)
    }

    private fun runPipeline(lslPath: String) {
        val psa = PredicateStateAnalysis(analysisContext.cm)
        val librarySpecification = LibslDescriptor(lslPath)

        executePipeline(cm, `package`) {
            +OriginalMapper(analysisContext.cm, originalContext.cm)
            +LoopSimplifier(analysisContext.cm)
            +LoopDeroller(analysisContext.cm)
            +LibslInstrumentator(cm, librarySpecification)
            +BranchAdapter(analysisContext.cm)
            +psa
            +MethodFieldAccessCollector(analysisContext, psa)
            +SetterCollector(analysisContext)
            +ExternalCtorCollector(analysisContext.cm, Visibility.PUBLIC)
        }

        executePipeline(cm, `package`) {
            +CallCiteChecker(analysisContext, `package`, psa)
            +ClassWriter(analysisContext, tempDir.toPath())
        }

        clearClassPath()
        DefectManager.emit()
    }
}