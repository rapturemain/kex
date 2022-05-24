package org.vorpal.research.kex.asm.transform

import org.vorpal.research.kex.KexTest
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.visitor.LoopAnalysis
import org.vorpal.research.kfg.visitor.pipelineStub
import kotlin.test.Test
import kotlin.test.assertTrue

class LoopDerollerTest : KexTest() {

    private fun checkLoops(method: Method) {
        if (method.isAbstract) return

        if (!method.hasLoops) return

        LoopSimplifier(cm, pipelineStub()).visit(method)
        var loops = LoopAnalysis(cm, pipelineStub()).analyse(method).loops
        for (loop in loops) {
            if (!loop.hasSinglePreheader) return
            if (!loop.hasSingleLatch) return
        }

        LoopDeroller(cm, pipelineStub()).visit(method)
        loops = LoopAnalysis(cm, pipelineStub()).analyse(method).loops
        assertTrue(loops.isEmpty())
    }

    @Test
    fun simpleLoopTest() {
        val `class` = cm["$packageName/LoopTests"]
        for (method in `class`.allMethods) {
            checkLoops(method)
        }
    }

    @Test
    fun icfpcLoopTest() {
        val `class` = cm["$packageName/Icfpc2018Test"]
        for (method in `class`.allMethods) {
            checkLoops(method)
        }
    }
}