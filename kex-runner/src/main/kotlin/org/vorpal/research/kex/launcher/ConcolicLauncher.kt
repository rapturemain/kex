package org.vorpal.research.kex.launcher

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.asm.analysis.concolic.InstructionConcolicChecker
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.manager.MethodWrapperInitializer
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.transform.SymbolicTraceCollector
import org.vorpal.research.kex.asm.transform.SystemExitTransformer
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.jacoco.CoverageReporter
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManager
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManagerProvider
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kfg.visitor.schedule
import org.vorpal.research.kthelper.logging.log

@ExperimentalSerializationApi
@InternalSerializationApi
class ConcolicLauncher(classPaths: List<String>, targetName: String) : KexLauncher(classPaths, targetName) {
    override fun createInstrumenter(context: ExecutionContext, pipeline: Pipeline): MethodVisitor {
        return SymbolicTraceCollector(context.cm, pipeline)
    }

    override fun preparePackage(ctx: ExecutionContext, pkg: Package) = executePipeline(ctx.cm, pkg) {
        +ClassInstantiationDetector(ctx.cm, this@executePipeline, Visibility.PRIVATE)
    }

    override fun launch() {
        val traceManager = InstructionTraceManager()
        runPipeline(context) {
            registerProvider(ExecutionContextProvider(context))
            registerProvider(InstructionTraceManagerProvider(traceManager))

            +ClassInstantiationDetector(context.cm, this@runPipeline, Visibility.PRIVATE)
            schedule<InstructionConcolicChecker>()
        }
        log.info(
            CoverageReporter(containerClassLoader).execute(context.cm, analysisLevel)
                .print(kexConfig.getBooleanValue("kex", "printDetailedCoverage", false))
        )
    }
}