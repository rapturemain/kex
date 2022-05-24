package org.vorpal.research.kex.asm.manager

import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.NameMapper
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.type.parseStringToType
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline

@JvmInline
value class ValueWrapper(
    val name: String
) {
    fun unwrap(nm: NameMapper): Value = nm.getValue(name)!!
}

data class BlockWrapper(
    val name: String
) {
    val insts = mutableListOf<String>()

    fun unwrap(method: Method): BasicBlock = method.getBlockByName(name)!!
}

data class MethodWrapper(
    val klass: String,
    val name: String,
    val argsTypes: List<String>,
    val returnType: String
) {
    fun unwrap(cm: ClassManager) = cm[klass].getMethod(
        name,
        parseStringToType(cm.type, returnType),
        *argsTypes.map { parseStringToType(cm.type, it) }.toTypedArray()
    )
}

var BasicBlock.wrapper: BlockWrapper?
    get() = MethodWrapperManager.getBlock(this)
    set(value) {
        MethodWrapperManager.addBlock(this, value)
    }

val Method.wrapper: MethodWrapper
    get() = MethodWrapper(
        klass.fullName,
        name,
        argTypes.map { it.asmDesc },
        returnType.asmDesc
    )


private object MethodWrapperManager {
    private val blockMappings = mutableMapOf<Method, MutableMap<BasicBlock, BlockWrapper?>>()

    fun initMethod(method: Method) {
        val map = blockMappings.getOrPut(method, ::mutableMapOf)
        for (block in method) {
            map[block] = BlockWrapper(block.name.toString()).also { bw ->
                bw.insts.addAll(block.instructions.map { it.name.toString() })
            }
        }
    }

    fun addBlock(block: BasicBlock, wrapper: BlockWrapper?) {
        val map = blockMappings.getOrPut(block.parent, ::mutableMapOf)
        map[block] = wrapper
    }

    fun getBlock(block: BasicBlock): BlockWrapper? {
        return blockMappings[block.parent]?.get(block)
    }
}

class MethodWrapperInitializer(override val cm: ClassManager, override val pipeline: Pipeline) : MethodVisitor {
    override fun cleanup() {}

    override fun visit(method: Method) {
        MethodWrapperManager.initMethod(method)
    }
}