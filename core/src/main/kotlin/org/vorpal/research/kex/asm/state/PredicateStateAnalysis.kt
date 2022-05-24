package org.vorpal.research.kex.asm.state

import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.Node
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.pass.AnalysisResult
import org.vorpal.research.kfg.visitor.pass.AnalysisVisitor
import org.vorpal.research.kthelper.KtException
import org.vorpal.research.kthelper.graph.NoTopologicalSortingException
import org.vorpal.research.kthelper.logging.log

class PredicateStateAnalysis(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : MethodVisitor, AnalysisResult {
    private val builders = hashMapOf<Method, PredicateStateBuilder>()

    override fun cleanup() {}

    private fun createBuilder(method: Method): PredicateStateBuilder {
        val builder = PredicateStateBuilder(method)
        try {
            builder.init()
        } catch (e: NoTopologicalSortingException) {
            log.error("Can't perform topological sorting of $method")
        } catch (e: KtException) {
            // during loop derolling we can create instructions,
            // that are not convertible into predicates
            log.error("Unexpected exception during PS building for $method: $e")
        }
        return builder
    }

    fun builder(method: Method) = builders.getOrPut(method) { createBuilder(method) }

    override fun visit(method: Method) {
        if (method !in builders) {
            builders[method] = createBuilder(method)
        }
    }
}

data class PredicateStateKfgAnalysis(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : AnalysisVisitor<PredicateStateAnalysis> {
    private val predicateStateAnalysis = PredicateStateAnalysis(cm, pipeline)

    override fun analyse(node: Node): PredicateStateAnalysis {
        return predicateStateAnalysis
    }
}