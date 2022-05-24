package org.vorpal.research.kex.evolutions


import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.analysis.IRVerifier
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.BodyBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.EmptyUsageContext.users
import org.vorpal.research.kfg.ir.value.MethodUsageContext
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.BinaryInst
import org.vorpal.research.kfg.ir.value.instruction.BinaryOpcode
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.PhiInst
import org.vorpal.research.kfg.ir.value.usageContext
import org.vorpal.research.kfg.type.IntType
import org.vorpal.research.kfg.visitor.Loop
import org.vorpal.research.kfg.visitor.LoopVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.addRequiredPass
import ru.spbstu.*

open class LoopOptimizer(cm: ClassManager, override val pipeline: Pipeline) : Evolutions(cm, pipeline), LoopVisitor {
    protected lateinit var ctx: MethodUsageContext
    protected val phiToEvo = mutableMapOf<PhiInst, Symbolic>()
    protected val freshVars = mutableMapOf<Loop, Var>()
    override val preservesLoopInfo get() = false
    override fun cleanup() {}

    override fun visit(method: Method) = method.usageContext.use {
        cleanup()
        if (!method.hasBody) return
        walkLoops(method).forEach { loop ->
            loop
                .header
                .takeWhile { it is PhiInst }
                .filterIsInstance<PhiInst>()
                .forEach {
                    loopPhis[it] = loop
                }
            loop.body.forEach { basicBlock ->
                basicBlock.forEach {
                    inst2loop.getOrPut(it) { loop }
                }
            }
        }

        for (b in method) for (i in b) {
            transform(i)
        }

        loopPhis.keys.forEach {
            phiToEvo[it] = evaluateEvolutions(buildPhiEquation(it), freshVars)
            println(phiToEvo[it])
        }


        val loops = method.getLoopInfo()
        loops.forEach {
            visitLoop(it)
        }
        updateLoopInfo(method)
        IRVerifier(cm).visit(method)
    }

    override fun visitLoop(loop: Loop) {
        super.visitLoop(loop)
        if (loop.allEntries.size != 1 || loop !in freshVars.keys) {
            return
        }
        insertInductive(loop)
        rebuild(loop)
        clearUnused(loop)
    }

    protected fun clearUnused(loop: Loop) {
        val unused = mutableListOf<Instruction>()
        for (b in loop.body) {
            for (i in b) {
                if (i is BinaryInst || i is PhiInst)
                    if (i.users.isEmpty()) {
                        unused += i
                    }
            }
            unused.forEach {
                it.clearUses(ctx)
                b -= it
            }
            unused.clear()
        }
    }

    protected fun insertBefore(block: BasicBlock, e: BasicBlock, loop: Loop) = with(ctx) {
        e.add(instructions.getJump(ctx, block))
        loop.addBlock(e)
        method.add(e)
        block.predecessors.forEach { preBlock ->
            e.addPredecessor(preBlock)
            e.addSuccessor(block)

            block.removePredecessor(preBlock)
            block.addPredecessor(e)

            preBlock.removeSuccessor(block)
            preBlock.addSuccessor(e)


            preBlock.remove(preBlock.instructions.last())
            preBlock.add(instructions.getJump(ctx, e))

        }
    }

    protected fun insertAfter(block: BasicBlock, e: BasicBlock, loop: Loop) = with(ctx) {
        val afterBlock = block.successors.first()
        insertBefore(afterBlock, e, loop)
    }

    protected fun insertInductive(loop: Loop)  = with(ctx) {
        val one = values.getInt(1)
        val tmpPhi = instructions.getPhi(ctx, IntType, mapOf())

        val newInstruction = instructions.getBinary(ctx, BinaryOpcode.ADD, one, tmpPhi.get())
        val newPhi = instructions.getPhi(
            ctx,
            IntType,
            mapOf(Pair(loop.preheader, one), Pair(loop.latch, newInstruction.get()))
        )
        var2inst[freshVars[loop]!!] = newPhi
        inst2var[newPhi] = freshVars[loop]!!
        tmpPhi.replaceAllUsesWith(newPhi)
        tmpPhi.clearUses()
        loop.header.insertBefore(loop.header.first(), newPhi)

        val updater = BodyBlock("loop.updater")
        updater += newInstruction
        insertBefore(loop.latch, updater, loop)
    }

    private fun reconstructPhi(phi: PhiInst, collector: MutableList<Instruction>): Value? {
        val evo = phiToEvo[phi] ?: return null
        return evo.generateCode(collector)
    }

    private fun Symbolic.generateCode(collector: MutableList<Instruction>): Value? {
        return when (this) {
            is Sum -> this.generateCode(collector)
            is Const -> this.generateCode()
            is Var -> this.generateCode()
            is Shift -> this.generateCode(collector)
            is Apply -> null
            is Product -> this.generateCode(collector)
        }
    }

    private fun Sum.generateCode(collector: MutableList<Instruction>): Value? {
        val lcm = lcm(this.constant.den, this.parts.values.fold(1L) { acc, v -> lcm(acc, v.den) })
        val results = mutableListOf<Value>()
        this.parts.forEach {
            val res = it.key.generateCode(collector) ?: return null
            val newInstruction =
                instructions.getBinary(ctx, BinaryOpcode.MUL, res, values.getInt((it.value * lcm).wholePart.toInt()))
            collector.add(newInstruction)
            results.add(newInstruction.get())
        }
        val res = results.drop(1).fold(results[0]) { acc, v ->
            val newValue = instructions.getBinary(ctx, BinaryOpcode.ADD, acc, v)
            collector.add(newValue)
            newValue
        }
        if (constant.num == 0L) {
            if (lcm == 1L) {
                return res
            }
            val divLcm = instructions.getBinary(ctx, BinaryOpcode.DIV, res, values.getInt(lcm.toInt()))
            collector.add(divLcm)
            return divLcm
        }
        val addConst =
            instructions.getBinary(ctx, BinaryOpcode.ADD, res, values.getInt((this.constant * lcm).wholePart.toInt()))
        collector.add(addConst)
        if (lcm == 1L) {
            return addConst
        }
        val divLcm = instructions.getBinary(ctx, BinaryOpcode.DIV, addConst, values.getInt(lcm.toInt()))
        collector.add(divLcm)
        return divLcm
    }

    private fun Product.generateCode(collector: MutableList<Instruction>): Value? {
        val results = mutableListOf<Value>()
        this.parts.forEach {
            if (it.value.den != 1L) return null
            val base = it.key.generateCode(collector) ?: return null
            var pre = base
            for (i in 1 until it.value.wholePart) {
                val newInst =
                    instructions.getBinary(ctx, BinaryOpcode.MUL, pre, base)
                collector.add(newInst)
                pre = newInst

            }
            results.add(pre)
        }
        val res = results.drop(1).fold(results[0]) { acc, v ->
            val newValue = instructions.getBinary(ctx, BinaryOpcode.MUL, acc, v)
            collector.add(newValue)
            newValue
        }
        if (constant.isWhole() && constant.wholePart == 1L) {
            return res
        }
        val mulConst =
            instructions.getBinary(ctx, BinaryOpcode.MUL, res, values.getInt(constant.num.toInt()))
        collector.add(mulConst)

        if (constant.isWhole()) {
            return mulConst
        }
        val divLcm = instructions.getBinary(ctx, BinaryOpcode.DIV, mulConst, values.getInt(constant.den.toInt()))
        collector.add(divLcm)
        return divLcm
    }

    private fun Const.generateCode(): Value? {
        return values.getConstant(this.value.wholePart)
    }

    private fun Var.generateCode(): Value {
        return var2inst[this]!!
    }

    private fun Apply.generateCode(collector: MutableList<Instruction>): Value? {
        return when (this) {
            is ShiftRight -> generateCode(collector)
            is ShiftLeft -> generateCode(collector)
            else -> null
        }
    }

    private fun ShiftLeft.generateCode(collector: MutableList<Instruction>): Value? {
        collector.add(
            instructions.getBinary(
                ctx,
                BinaryOpcode.SHL,
                base.generateCode(collector) ?: return null,
                shift.generateCode(collector) ?: return null
            )
        )
        return collector.last()
    }

    private fun ShiftRight.generateCode(collector: MutableList<Instruction>): Value? {
        collector.add(
            instructions.getBinary(
                ctx,
                BinaryOpcode.SHR,
                base.generateCode(collector) ?: return null,
                shift.generateCode(collector) ?: return null
            )
        )
        return collector.last()
    }

    private fun lcm(a: Long, b: Long): Long = (a / gcd(a, b)) * b

    protected fun rebuild(loop: Loop): List<Instruction>? {
        val phis = loop.body.flatMap { it.instructions }.mapNotNull { it as? PhiInst }
            .mapNotNull { if (phiToEvo.containsKey(it)) it else null }
        val newBlock = mutableListOf<Instruction>()
        val phiList = mutableListOf<Pair<PhiInst, Value>>()
        return if (phis.all {
                val res = reconstructPhi(it, newBlock) ?: return@all false
                phiList.add(Pair(it, res))
                true
            }) {
                phiList.forEach{ it.first.replaceAllUsesWith(ctx, it.second)}
            newBlock
        } else {
            null
        }
    }
}
