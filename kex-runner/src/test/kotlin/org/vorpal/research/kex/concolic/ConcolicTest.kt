package org.vorpal.research.kex.concolic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.concolic.InstructionConcolicChecker
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.transform.SymbolicTraceCollector
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.jacoco.CoverageReporter
import org.vorpal.research.kex.launcher.ClassLevel
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManager
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManagerProvider
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kfg.visitor.schedule
import org.vorpal.research.kthelper.logging.log
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.assertEquals

@ExperimentalSerializationApi
@InternalSerializationApi
abstract class ConcolicTest : KexRunnerTest() {

    override fun createTraceCollector(context: ExecutionContext, pipeline: Pipeline) =
        SymbolicTraceCollector(context.cm, pipeline)

    fun assertCoverage(klass: Class, expectedCoverage: Double = 1.0) {
        val traceManager = InstructionTraceManager()
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext.cm, this@executePipeline, Visibility.PRIVATE)
        }
        executePipeline(analysisContext.cm, klass) exec@{
            registerProvider(ExecutionContextProvider(analysisContext))
            registerProvider(InstructionTraceManagerProvider(traceManager))

            schedule(ClassInstantiationDetector(analysisContext.cm, this@exec, Visibility.PRIVATE), false)
            schedule<InstructionConcolicChecker>()
        }
        val coverage = CoverageReporter(jar.classLoader as URLClassLoader).execute(klass.cm, ClassLevel(klass))
        log.debug(coverage.print(true))
        assertEquals(expectedCoverage, coverage.instructionCoverage.ratio)
    }
}