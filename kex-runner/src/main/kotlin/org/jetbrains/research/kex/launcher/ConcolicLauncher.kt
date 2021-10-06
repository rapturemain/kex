package org.jetbrains.research.kex.launcher

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.asm.analysis.concolic.InstructionConcolicChecker
import org.jetbrains.research.kex.asm.manager.MethodWrapperInitializer
import org.jetbrains.research.kex.asm.transform.SymbolicTraceCollector
import org.jetbrains.research.kex.asm.transform.SystemExitTransformer
import org.jetbrains.research.kex.trace.symbolic.InstructionTraceManager
import org.jetbrains.research.kfg.visitor.MethodVisitor
import org.jetbrains.research.kthelper.logging.log
import java.util.*

@ExperimentalSerializationApi
@InternalSerializationApi
class ConcolicLauncher(classPaths: List<String>, targetName: String) : KexLauncher(classPaths, targetName)  {
    override fun createInstrumenter(context: ExecutionContext): MethodVisitor {
        return SymbolicTraceCollector(context)
    }

    override fun launch() {
        val traceManager = InstructionTraceManager()
        val cm = createCoverageCounter(context.cm, traceManager)

        runPipeline(context) {
            +MethodWrapperInitializer(context.cm)
            +SystemExitTransformer(context.cm)
            +InstructionConcolicChecker(context, traceManager)
            +cm
        }
        val coverage = cm.totalCoverage
        log.info(
            "Overall summary for ${cm.methodInfos.size} methods:\n" +
                    "body coverage: ${String.format(Locale.ENGLISH, "%.2f", coverage.bodyCoverage)}%\n" +
                    "full coverage: ${String.format(Locale.ENGLISH, "%.2f", coverage.fullCoverage)}%"
        )
    }
}