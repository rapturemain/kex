package org.vorpal.research.kex.asm.state

import org.junit.Assert.assertNotNull
import org.vorpal.research.kex.KexTest
import org.vorpal.research.kex.asm.transform.LoopDeroller
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.UnreachableInst
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kfg.visitor.schedule
import org.vorpal.research.kthelper.graph.NoTopologicalSortingException
import kotlin.test.Test

class PredicateStateBuilderTest : KexTest() {

    private fun performPSA(method: Method): PredicateStateBuilder {
        if (method.hasLoops) {
            executePipeline(cm, listOf(method)) {
                schedule<LoopDeroller>()
            }
        }

        val psa = PredicateStateBuilder(method)
        psa.init()
        return psa
    }

    @Test
    fun testSimplePSA() {
        for (`class` in cm.getByPackage(`package`)) {
            for (method in `class`.allMethods) {
                if (method.isAbstract) continue

                val psa = try {
                    performPSA(method)
                } catch (e: NoTopologicalSortingException) {
                    continue
                }

                val catchBlocks = method.catchBlocks
                method.filter { it !in catchBlocks }
                        .flatten()
                        .filter { it !is UnreachableInst }
                        .forEach {
                    assertNotNull(psa.getInstructionState(it))
                }
            }
        }
    }
}