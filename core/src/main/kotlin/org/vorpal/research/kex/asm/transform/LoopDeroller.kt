package org.vorpal.research.kex.asm.transform

import org.vorpal.research.kex.asm.manager.MethodWrapperInitializer
import org.vorpal.research.kex.asm.manager.wrapper
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.evolutions.LoopOptimizer
import org.vorpal.research.kex.evolutions.defaultVar
import org.vorpal.research.kex.evolutions.evaluateEvolutions
import org.vorpal.research.kex.evolutions.walkLoops
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.BodyBlock
import org.vorpal.research.kfg.ir.CatchBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.*
import org.vorpal.research.kfg.ir.value.instruction.*
import org.vorpal.research.kfg.visitor.Loop
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.addRequiredPass
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.graph.GraphTraversal
import org.vorpal.research.kthelper.graph.NoTopologicalSortingException
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.toInt
import ru.spbstu.Var
import java.lang.Math.abs
import java.lang.Math.min


private val derollCount = kexConfig.getIntValue("loop", "derollCount", 3)
private val maxDerollCount = kexConfig.getIntValue("loop", "maxDerollCount", 0)
private val useBackstabbing = kexConfig.getBooleanValue("loop", "useBackstabbing", false)

class LoopDeroller(override val cm: ClassManager, override val pipeline: Pipeline) : LoopOptimizer(cm, pipeline) {
    companion object {
        const val DEROLLED_POSTFIX = ".deroll"

        val blockMapping = hashMapOf<Method, MutableMap<BasicBlock, BasicBlock>>()
        val unreachableBlocks = hashMapOf<Method, MutableSet<BasicBlock>>()
    }

    class InvalidLoopException : Exception()

    private data class State(
        val header: BasicBlock,
        val latch: BasicBlock,
        val terminatingBlock: BasicBlock,
        val continueOnTrue: Boolean,
        var lastHeader: BasicBlock,
        var lastLatch: BasicBlock,
        val blockMappings: MutableMap<BasicBlock, BasicBlock>,
        val instMappings: MutableMap<Value, Value>
    ) {
        companion object {
            fun createState(loop: Loop): State {
                val terminatingBlock = run {
                    var current = loop.latch
                    while (current.terminator is JumpInst) {
                        current = current.successors.first()
                    }
                    current
                }
                val continueOnTrue = when {
                    terminatingBlock.terminator.successors.isEmpty() -> false
                    terminatingBlock.terminator.successors.size == 1 -> false
                    else -> terminatingBlock.terminator.successors.first() in loop
                }

                val state = State(
                    loop.header, loop.latch, terminatingBlock,
                    continueOnTrue, loop.header, loop.preheader,
                    hashMapOf(), hashMapOf()
                )
                loop.body.flatten().forEach { state[it] = it }

                return state
            }
        }

        operator fun set(block: BasicBlock, value: BasicBlock) {
            blockMappings[block] = value
        }

        operator fun set(value: Value, newValue: Value) {
            instMappings[value] = newValue
        }

        operator fun get(block: BasicBlock) = blockMappings[block] ?: block
        operator fun get(value: Value) = instMappings.getValue(value)

        fun getOrDefault(block: BasicBlock, default: BasicBlock) = blockMappings.getOrDefault(block, default)
        fun getOrDefault(value: Value, default: Value) = instMappings.getOrDefault(value, default)
    }

    override fun registerPassDependencies() {
        addRequiredPass<LoopSimplifier>()
        addRequiredPass<MethodWrapperInitializer>()
    }

    override fun cleanup() {}

    override val preservesLoopInfo get() = false

    override fun visit(method: Method) = method.usageContext.use {
        if (!method.hasBody) return
        ctx = it
        try {
            precalculateEvolutions(method)
            val loops = method.getLoopInfo()
            loops.forEach { loop -> freshVars[loop] = Var.fresh("iteration") }
            loops.forEach { visitLoop(it) }
            updateLoopInfo(method)
        } catch (e: InvalidLoopException) {
            log.error("Can't deroll loops of method $method")
        } catch (e: NoTopologicalSortingException) {
            log.error("Can't perform topological sorting of loops of method $method")
        }
    }


    override fun visitLoop(loop: Loop) {
        loop.subLoops.forEach { visitLoop(it) }
        if (loop.allEntries.size != 1) throw InvalidLoopException()
        if (loop.loopExits.isEmpty()) throw InvalidLoopException()
        loop.method ?: unreachable { log.error("Can't get method of loop") }
        val blockOrder = getBlockOrder(loop)
        // init state
        unroll(
            loop,
            if (useBackstabbing && tryBackstabbing(loop, blockOrder.first())) 1 else
            getDerollCount(loop)
        )
    }

    private fun unroll(loop: Loop, derollCount: Int) = with(ctx) {
        val method = loop.method ?: unreachable { log.error("Can't get method of loop") }
        val blockOrder = getBlockOrder(loop)
        val state = State.createState(loop)
        val body = loop.body.toMutableList().onEach { loop.removeBlock(it) }

        log.debug("Method $method, unrolling loop $loop to $derollCount iterations")

        // save current phi instructions of method
        val methodPhis = method.filter { it !in body }.flatten().mapNotNull { it as? PhiInst }
        val methodPhiMappings = methodPhis.associateWith { phi ->
            phi.incomings.filterNot { it.key in body }.toMutableMap()
        }.toMutableMap()

        val methodBlockMapping = blockMapping.getOrPut(method, ::mutableMapOf)

        // deroll loop for given number of iterations
        for (iteration in 0..derollCount) {
            state.blockMappings.clear()
            for (block in blockOrder) {
                val copy = copyBlock(block)
                state[block] = copy
                if (loop.hasParent) {
                    loop.parent.addBlock(copy)
                }
                methodBlockMapping[copy] = block
            }

            for (block in blockOrder) {
                copyBlockConnections(state, block)
            }

            for (block in blockOrder) {
                copyBlockInstructions(state, block)

                for (phi in methodPhis) {
                    if (phi.incomings.contains(block)) {
                        val value = phi.incomings.getValue(block)
                        methodPhiMappings[phi]?.put(state[block], state.getOrDefault(value, value))
                    }
                }
            }

            state.lastLatch = state[state.latch]
            state.lastHeader = state[state.header]

            for (block in blockOrder) {
                val mapping = state[block]
                method.addBefore(state.header, mapping)
                mapping.wrapper = block.wrapper
                if (mapping is CatchBlock) method.addCatchBlock(mapping)
            }

            val phiMappings = blockOrder
                .flatten()
                .zip(blockOrder.map { state[it] }.flatten())
                .filter { it.first is PhiInst }
            remapBlockPhis(state, phiMappings)
        }

        val unreachableBlock = BodyBlock("unreachable")
        unreachableBlock.add(inst(cm) { unreachable() })
        method.add(unreachableBlock)
        unreachableBlocks.getOrPut(method, ::hashSetOf).add(unreachableBlock)
        unreachableBlock.wrapper = null

        // remap blocks of last iteration to actual method blocks
        val lastTerminator = state[state.terminatingBlock]
        val continueBlock = lastTerminator.terminator.successors[(!state.continueOnTrue).toInt()]
        lastTerminator.replaceSuccessorUsesOf(continueBlock, unreachableBlock)
        for (block in blockOrder.reversed()) {
            if (block == state.terminatingBlock) break
            val copy = state[block]
            copy.predecessors.toTypedArray().forEach { it.removeSuccessor(copy) }
            copy.successors.toTypedArray().forEach { it.removePredecessor(copy) }
            method.remove(copy)
        }

        // cleanup loop
        cleanupBody(method, body)
        remapMethodPhis(methodPhis, methodPhiMappings)
    }

    private fun getMinDerollCount(count: Int): Int = when {
        maxDerollCount > 0 -> min(maxDerollCount, count)
        else -> count
    }

    private fun getDerollCount(loop: Loop): Int {
        val tripCount = getConstantTripCount(loop)
        return when {
            tripCount > 0 -> getMinDerollCount(tripCount)
            else -> getMinDerollCount(derollCount)
        }
    }

    private fun getOpcodeConstant(opcode: CmpOpcode, constant: IntConstant) = when (opcode) {
        CmpOpcode.LE, CmpOpcode.GE -> constant
        else -> values.getInt(constant.value + 1) as IntConstant
    }

    private fun getConstantTripCount(loop: Loop): Int {
        val header = loop.header
        val preheader = loop.preheader
        val latch = loop.latch

        val branch = header.terminator as? BranchInst ?: return -1
        val cmp = branch.cond as? CmpInst ?: return -1
        val (value, max) = when {
            cmp.lhv is IntConstant -> cmp.rhv to getOpcodeConstant(cmp.opcode, cmp.lhv as IntConstant)
            cmp.rhv is IntConstant -> cmp.lhv to getOpcodeConstant(cmp.opcode, cmp.rhv as IntConstant)
            else -> return -1
        }

        val (init, updated) = when (value) {
            is PhiInst -> {
                if (value.parent != header) return -1

                val incomings = value.incomings
                if (incomings.size != 2) return -1
                incomings.getValue(preheader) to incomings.getValue(latch)
            }
            else -> return -1
        }

        if (init !is IntConstant) return -1

        val update = when (updated) {
            is BinaryInst -> {
                val (updateValue, updateSize) = when {
                    updated.rhv is IntConstant -> updated.lhv to (updated.rhv as IntConstant)
                    updated.lhv is IntConstant -> updated.rhv to (updated.lhv as IntConstant)
                    else -> return -1
                }
                when {
                    updateValue != value -> return -1
                    init.value > max.value && updated.opcode == BinaryOpcode.SUB -> updateSize
                    init.value < max.value && updated.opcode == BinaryOpcode.ADD -> updateSize
                    else -> return -1
                }
            }
            else -> return -1
        }

        return abs((max.value - init.value) / update.value)
    }

    private fun getBlockOrder(loop: Loop): List<BasicBlock> = with(ctx) {
        val latch = loop.latch
        val header = loop.header
        latch.removeSuccessor(header)
        loop.body.mapNotNull { it as? CatchBlock }.forEach { cb -> cb.allPredecessors.forEach { it.addSuccessor(cb) } }
        val order = GraphTraversal(loop).topologicalSort()
        loop.body.mapNotNull { it as? CatchBlock }
            .forEach { cb -> cb.allPredecessors.forEach { it.removeSuccessor(cb) } }
        latch.addSuccessor(header)
        return order.map { it.block }
    }

    private fun copyBlock(block: BasicBlock) = when (block) {
        is BodyBlock -> BodyBlock("${block.name.name}$DEROLLED_POSTFIX")
        is CatchBlock -> CatchBlock("${block.name.name}$DEROLLED_POSTFIX", block.exception)
    }

    private fun copyBlockConnections(state: State, original: BasicBlock) = with(ctx) {
        val derolled = state[original]
        when (original) {
            state.header -> {
                state.lastLatch.replaceUsesOf(state.lastHeader, derolled)
                derolled.addPredecessor(state.lastLatch)
            }
            else -> {
                val predecessors = original.predecessors.map { state[it] }
                derolled.addPredecessors(*predecessors.toTypedArray())
                predecessors.forEach { it.addSuccessor(derolled) }
            }
        }
        if (original is CatchBlock) {
            val throwers = original.throwers.map { state.getOrDefault(it, it) }
            derolled as CatchBlock
            derolled.addThrowers(throwers)
            throwers.forEach { it.addHandler(derolled) }
        }
        for (handle in original.handlers) {
            val derolledHandle = (state.getOrDefault(handle, handle)) as CatchBlock
            derolled.addHandler(derolledHandle)
            derolledHandle.addThrowers(listOf(derolled))
        }

        val successors = original.successors.map {
            state.getOrDefault(it, it)
        }
        derolled.addSuccessors(*successors.toTypedArray())
        successors.forEach { it.addPredecessor(derolled) }
    }

    private fun copyBlockInstructions(state: State, original: BasicBlock) = with(ctx) {
        val newBlock = state[original]
        for (inst in original) {
            val updated = inst.update(this, state.instMappings, inst.location)
            state[inst] = updated
            newBlock += updated

            if (updated is BlockUser) {
                state.blockMappings.forEach { (original, derolled) -> updated.replaceUsesOf(original, derolled) }
            }

            if (updated is PhiInst && original == state.header) {
                val map = mapOf(state[state.latch] to state.lastLatch)
                val previousMap = updated.incomings
                map.forEach { (o, t) -> updated.replaceUsesOf(o, t) }

                if (updated.predecessors.toSet().size == 1) {
                    val actual = previousMap.getValue(state.lastLatch)
                    val mappedActual = if (actual is NullConstant) {
                        val newInst = inst(cm) { actual `as` updated.type }
                        newBlock.insertBefore(updated, newInst)
                        newInst
                    } else actual
                    updated.replaceAllUsesWith(mappedActual)
                    newBlock.remove(updated)
                    state[inst] = mappedActual
                }
            }
        }
    }

    private fun remapBlockPhis(state: State, phiMappings: List<Pair<Instruction, Instruction>>) = with(ctx) {
        for ((orig, new) in phiMappings) {
            if (new is PhiInst) {
                val bb = new.parent
                val incomings = new.incomings.filter { it.key in bb.predecessors }
                val phiValue = when (incomings.size) {
                    1 -> {
                        bb -= new
                        incomings.values.first()
                    }
                    else -> {
                        val newPhi = inst(cm) { phi(new.type, incomings) }
                        bb.replace(new, newPhi)
                        new.clearUses()
                        newPhi
                    }
                }
                state[orig] = phiValue
                new.replaceAllUsesWith(phiValue)
            }
        }
    }

    private fun remapMethodPhis(phis: List<PhiInst>, newMappings: Map<PhiInst, Map<BasicBlock, Value>>) = with(ctx) {
        for (phi in phis) {
            val newPhi = inst(cm) { phi(phi.type, newMappings.getValue(phi)) }
            val newPhiClone = newPhi.update(ctx, loc = phi.location)
            newPhi.replaceAllUsesWith(newPhiClone)
            newPhi.clearUses()

            val bb = phi.parent
            bb.insertBefore(phi, newPhiClone)
            phi.replaceAllUsesWith(newPhiClone)
            phi.clearUses()
            bb -= phi
        }
    }

    private fun cleanupBody(method: Method, body: List<BasicBlock>) = with(ctx) {
        for (block in body) {
            block.predecessors.toTypedArray().forEach { block.removePredecessor(it) }
            block.successors.toTypedArray().forEach { block.removeSuccessor(it) }
            method.remove(block)
        }

        val loopThrowers = method.catchEntries.associateWith { catch ->
            body.filter { it.handlers.contains(catch) }
        }

        for ((catch, throwers) in loopThrowers) {
            throwers.forEach {
                catch.removeThrower(it)
                it.removeHandler(catch)
            }
        }
    }

    private fun precalculateEvolutions(method: Method) {
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
    }

    private fun tryBackstabbing(loop: Loop, firstBlock: BasicBlock): Boolean {
        if (loop.latches.isEmpty() || loop.preheaders.isEmpty() || loop !in loopPhis.values) {
            return false
        }

        for (b in loop.allEntries) for (i in b) {
            transform(i)
        }
        loopPhis.keys.forEach {
            try {
                if (loop.latch in it.incomings &&
                        loop.preheader in it.incomings) {
                    phiToEvo[it] = evaluateEvolutions(buildPhiEquation(it), freshVars)
                }
            } catch (e: StackOverflowError) {
                return false
            }
        }

        val inst = createInductive(loop)
        val block = rebuild(loop) ?: return false
        if (block.isEmpty()) return false
        val a = firstBlock.last()
        firstBlock.remove(a)
        firstBlock.add(inst)
        firstBlock.addAll(block)
        firstBlock.add(a)
        clearUnused(loop)
        return true
    }

    private fun createInductive(loop: Loop): Instruction {
        val a = freshVars.getOrPut(loop, defaultVar())
        val newInst = instructions.getUnknownValueInst(ctx, a.name, types.intType)
        var2inst[a] = newInst
        inst2var[newInst] = a
        return newInst
    }
}
