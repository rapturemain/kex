package org.vorpal.research.kex.asm.analysis.concolic

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.annotations.AnnotationManager
import org.vorpal.research.kex.asm.analysis.concolic.bfs.BfsPathSelectorImpl
import org.vorpal.research.kex.asm.analysis.concolic.cgs.ContextGuidedSelector
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.manager.isImpactable
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.state.PredicateStateKfgAnalysis
import org.vorpal.research.kex.asm.transform.SystemExitTransformer
import org.vorpal.research.kex.compile.JavaCompilerDriver
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.parameters.asDescriptors
import org.vorpal.research.kex.parameters.concreteParameters
import org.vorpal.research.kex.parameters.filterStaticFinals
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.ExecutorTestCasePrinter
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.reanimator.collector.SetterAnalysisResult
import org.vorpal.research.kex.reanimator.collector.SetterCollector
import org.vorpal.research.kex.smt.Checker
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.transformer.*
import org.vorpal.research.kex.trace.TraceManager
import org.vorpal.research.kex.trace.runner.SymbolicExternalTracingRunner
import org.vorpal.research.kex.trace.runner.generateParameters
import org.vorpal.research.kex.trace.symbolic.ExecutionResult
import org.vorpal.research.kex.trace.symbolic.InstructionTrace
import org.vorpal.research.kex.trace.symbolic.InstructionTraceManagerProvider
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.util.getJunit
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.visitor.*
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.debug
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.logging.warn
import org.vorpal.research.kthelper.tryOrNull
import java.nio.file.Path

private class CompilerHelper(val ctx: ExecutionContext) {
    private val junitJar = getJunit()!!
    private val outputDir = kexConfig.getPathValue("kex", "outputDir")!!
    val compileDir = outputDir.resolve(
        kexConfig.getPathValue("compile", "compileDir", "compiled/")
    ).also {
        it.toFile().mkdirs()
    }

    fun compileFile(file: Path) {
        val compilerDriver = JavaCompilerDriver(
            listOf(*ctx.classPath.toTypedArray(), junitJar.path), compileDir
        )
        compilerDriver.compile(listOf(file))
    }
}

@ExperimentalSerializationApi
@InternalSerializationApi
class InstructionConcolicChecker(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : MethodVisitor {
    val ctx get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()
    val traceManager get() = getProvider<InstructionTraceManagerProvider, TraceManager<InstructionTrace>>().provide()

    private val compilerHelper = CompilerHelper(ctx)

    private val timeLimit = kexConfig.getLongValue("concolic", "timeLimit", 100000L)
    private val searchStrategy = kexConfig.getStringValue("concolic", "searchStrategy", "bfs")
    private var testIndex = 0

    override fun cleanup() {}

    override fun registerPassDependencies() {
        addRequiredPass<ClassInstantiationDetector>()
        addRequiredPass<SystemExitTransformer>()

        addRequiredProvider<ExecutionContextProvider>()
        addRequiredProvider<InstructionTraceManagerProvider>()
    }

    override fun registerAnalysisDependencies() {
        addRequiredAnalysis<PredicateStateKfgAnalysis>()
        addRequiredAnalysis<SetterCollector>()
    }

    override fun visit(method: Method) {
        if (method.isStaticInitializer || !method.hasBody) return
        if (!method.isImpactable) return

        log.debug { "Processing method $method" }
        log.debug { method.print() }

        runBlocking {
            try {
                withTimeout(timeLimit) {
                    processMethod(method)
                }
                log.debug { "Method $method processing is finished normally" }
            } catch (e: TimeoutCancellationException) {
                log.debug { "Method $method processing is finished with timeout exception" }
            }
        }
    }

    private fun getRandomTrace(method: Method): ExecutionResult? = tryOrNull {
        val params = ctx.random.generateParameters(ctx.loader, method) ?: return null
        collectTraceFromAny(method, params)
    }

    private fun collectTraceFromAny(method: Method, parameters: Parameters<Any?>): ExecutionResult? =
        collectTrace(method, parameters.asDescriptors)

    private fun collectTrace(method: Method, parameters: Parameters<Descriptor>): ExecutionResult? = tryOrNull {
        val generator = UnsafeGenerator(
            ctx,
            getAnalysis<SetterCollector, SetterAnalysisResult>(method.klass),
            method,
            method.klassName + testIndex++)
        generator.generate(parameters)
        val testFile = generator.emit()

        compilerHelper.compileFile(testFile)
        collectTrace(generator.testKlassName)
    }

    private fun collectTrace(klassName: String): ExecutionResult {
        val runner = SymbolicExternalTracingRunner(ctx)
        return runner.run(klassName, ExecutorTestCasePrinter.SETUP_METHOD, ExecutorTestCasePrinter.TEST_METHOD)
    }

    private fun prepareState(
        method: Method,
        state: PredicateState,
        typeMap: TypeInfoMap = emptyMap<Term, KexType>().toTypeMap()
    ): PredicateState = transform(state) {
        val predicateStateAnalysis = getAnalysis<PredicateStateKfgAnalysis, PredicateStateAnalysis>(method)

        +KexRtAdapter(cm)
        +RecursiveInliner(predicateStateAnalysis) { index, psa ->
            ConcolicInliner(
                ctx,
                typeMap,
                psa,
                inlineIndex = index
            )
        }
        +ClassAdapter(cm)
        +AnnotationAdapter(method, AnnotationManager.defaultLoader)
        +IntrinsicAdapter
        +KexIntrinsicsAdapter()
        +ReflectionInfoAdapter(method, ctx.loader)
        +Optimizer()
        +ConstantPropagator
        +BoolTypeAdapter(method.cm.type)
        +ClassMethodAdapter(method.cm)
        +ConstEnumAdapter(ctx)
        +ConstStringAdapter(method.cm.type)
        +StringMethodAdapter(ctx.cm)
        +ConcolicArrayLengthAdapter()
        +FieldNormalizer(method.cm)
        +TypeNameAdapter(ctx.types)
    }

    private fun check(method: Method, state: SymbolicState): ExecutionResult? {
        val predicateStateAnalysis = getAnalysis<PredicateStateKfgAnalysis, PredicateStateAnalysis>(method)
        val checker = Checker(method, ctx, predicateStateAnalysis)
        val query = state.path.asState()
        val concreteTypeInfo = state.concreteValueMap
            .mapValues { it.value.type }
            .filterValues { it.isJavaRt }
            .mapValues { it.value.rtMapped }
            .toTypeMap()
        val preparedState = prepareState(method, state.state + query, concreteTypeInfo)
        val result = checker.check(preparedState)
        if (result !is Result.SatResult) return null

        return tryOrNull {
            val params = generateFinalDescriptors(method, ctx, result.model, checker.state)
                .filterStaticFinals(cm)
                .concreteParameters(ctx.cm)
            collectTrace(method, params)
        }
    }

    private fun buildPathSelector(traceManager: TraceManager<InstructionTrace>) = when (searchStrategy) {
        "bfs" -> BfsPathSelectorImpl(ctx.types, traceManager)
        "cgs" -> ContextGuidedSelector(ctx.types, traceManager)
        else -> unreachable { log.error("Unknown type of search strategy $searchStrategy") }
    }

    private suspend fun processMethod(method: Method) {
        testIndex = 0
        val pathIterator = buildPathSelector(traceManager)
        getRandomTrace(method)?.let {
            pathIterator.addExecutionTrace(method, it)
        }
        yield()

        while (pathIterator.hasNext()) {
            val state = pathIterator.next()
            log.debug { "Checking state: $state" }
            yield()

            val newState = check(method, state) ?: continue
            if (newState.trace.isEmpty()) {
                log.warn { "Collected empty state from $state" }
                continue
            }
            pathIterator.addExecutionTrace(method, newState)
            yield()
        }
    }

}