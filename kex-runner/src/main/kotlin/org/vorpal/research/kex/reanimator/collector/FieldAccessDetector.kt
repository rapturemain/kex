package org.vorpal.research.kex.reanimator.collector

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.annotations.AnnotationManager
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.state.PredicateStateKfgAnalysis
import org.vorpal.research.kex.asm.transform.BranchAdapter
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.transformer.*
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Field
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.Node
import org.vorpal.research.kfg.visitor.*
import org.vorpal.research.kfg.visitor.pass.AnalysisResult
import org.vorpal.research.kfg.visitor.pass.AnalysisVisitor
import org.vorpal.research.kthelper.tryOrNull

class MethodFieldAccessCollector(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : AnalysisVisitor<MethodFieldAccessResult> {
    val ctx: ExecutionContext
        get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()

    override fun cleanup() {}

    override fun registerPassDependencies() {
        addRequiredProvider<ExecutionContextProvider>()
    }

    override fun analyse(node: Node): MethodFieldAccessResult {
        require(node is Method) { "Tried to execute analysis on non method" }
        require(!node.klass.isJavaRt)

        return MethodFieldAccessResult(
            tryOrNull {
                val predicateStateAnalysis = getAnalysis<PredicateStateKfgAnalysis, PredicateStateAnalysis>(node)
                val methodState = predicateStateAnalysis.builder(node).methodState ?: return@tryOrNull setOf()
                val preparedState = prepareState(node, methodState)
                collectFieldAccesses(ctx, preparedState)
            } ?: setOf()
        )
    }

    private fun prepareState(method: Method, ps: PredicateState) = transform(ps) {
        val predicateStateAnalysis = getAnalysis<PredicateStateKfgAnalysis, PredicateStateAnalysis>(method)
        +StringMethodAdapter(ctx.cm)
        +AnnotationAdapter(method, AnnotationManager.defaultLoader)
        +KexRtAdapter(ctx.cm)
        +MethodInliner(predicateStateAnalysis)
        +ClassAdapter(cm)
        +ClassMethodAdapter(cm)
        +ConstEnumAdapter(ctx)
        +ConstStringAdapter(cm.type)
        +TypeNameAdapter(types)
    }
}

data class MethodFieldAccessResult(val fieldAccesses: Set<Field>) : AnalysisResult
