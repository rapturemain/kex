package org.vorpal.research.kex.asm.analysis.testgen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.state.PredicateStateKfgAnalysis
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.reanimator.collector.SetterAnalysisResult
import org.vorpal.research.kex.reanimator.collector.SetterCollector
import org.vorpal.research.kex.trace.TraceManager
import org.vorpal.research.kex.trace.`object`.ActionTrace
import org.vorpal.research.kex.trace.`object`.ActionTraceManagerProvider
import org.vorpal.research.kex.trace.runner.RandomSymbolicTracingRunner
import org.vorpal.research.kex.trace.runner.ReanimatingRandomObjectTracingRunner
import org.vorpal.research.kex.trace.symbolic.InstructionTrace
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManagerProvider
import org.vorpal.research.kex.util.TimeoutException
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.NameMapperContext
import org.vorpal.research.kfg.visitor.*
import org.vorpal.research.kthelper.logging.debug
import org.vorpal.research.kthelper.logging.log

private val runs: Int by lazy {
    kexConfig.getIntValue("random-runner", "attempts", 10)
}
private val runner: Boolean by lazy {
    kexConfig.getBooleanValue("random-runner", "enabled", false)
}

@InternalSerializationApi
@ExperimentalSerializationApi
class RandomChecker(
    override val cm: ClassManager,
    override val pipeline: Pipeline,
    val visibilityLevel: Visibility
) : MethodVisitor {
    private val nameContext = NameMapperContext()

    val ctx: ExecutionContext
        get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()

    val tm: TraceManager<ActionTrace>
        get() = getProvider<ActionTraceManagerProvider, TraceManager<ActionTrace>>().provide()

    override fun registerPassDependencies() {
        addSoftDependencyPass<MethodChecker>()
        addSoftDependencyPass<DescriptorChecker>()

        addRequiredProvider<ActionTraceManagerProvider>()
        addRequiredProvider<ExecutionContextProvider>()
    }

    override fun registerAnalysisDependencies() {
        addRequiredAnalysis<PredicateStateKfgAnalysis>()
        addRequiredAnalysis<SetterCollector>()
    }

    override fun cleanup() {
        nameContext.clear()
    }

    override fun visit(method: Method) {
        super.visit(method)
        if (!runner) return
        if (method.klass.isSynthetic) return
        if (method.isAbstract || method.isConstructor || method.isStaticInitializer) return

        val psa = getAnalysis<PredicateStateKfgAnalysis, PredicateStateAnalysis>(method)
        val setters = getAnalysis<SetterCollector, SetterAnalysisResult>(method.klass)
        val randomRunner = ReanimatingRandomObjectTracingRunner(ctx, nameContext, psa, setters, visibilityLevel, method)

        repeat(runs) { _ ->
            try {
                val trace = randomRunner.run() ?: return@repeat
                tm[method] = trace
            } catch (e: TimeoutException) {
                log.warn("Method $method failed with timeout, skipping it")
                return
            }
        }

        randomRunner.emit()
    }
}

@InternalSerializationApi
@ExperimentalSerializationApi
class SymbolicRandomChecker(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : MethodVisitor {
    private val nameContext = NameMapperContext()

    val ctx: ExecutionContext
        get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()

    val tm: TraceManager<InstructionTrace>
        get() = getProvider<InstructionTraceManagerProvider, TraceManager<InstructionTrace>>().provide()

    override fun registerPassDependencies() {
        addSoftDependencyPass<MethodChecker>()
        addSoftDependencyPass<DescriptorChecker>()

        addRequiredProvider<InstructionTraceManagerProvider>()
        addRequiredProvider<ExecutionContextProvider>()
    }

    override fun cleanup() {
        nameContext.clear()
    }

    override fun visit(method: Method) {
        super.visit(method)
        if (!runner) return
        if (method.klass.isSynthetic) return
        if (method.isAbstract || method.isConstructor || method.isStaticInitializer) return

        val randomRunner = RandomSymbolicTracingRunner(ctx, nameContext, method)

        repeat(runs) { _ ->
            try {
                log.debug("Running method $method")
                val trace = randomRunner.run() ?: return@repeat
                tm[method] = trace.trace
                log.debug(trace)
            } catch (e: TimeoutException) {
                log.warn("Method $method failed with timeout, skipping it")
                return
            }
        }
    }
}