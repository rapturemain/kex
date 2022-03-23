package org.jetbrains.research.kex.reanimator.actionsequence.generator

import org.jetbrains.research.kex.descriptor.ArrayDescriptor
import org.jetbrains.research.kex.descriptor.Descriptor
import org.jetbrains.research.kex.descriptor.ObjectDescriptor
import org.jetbrains.research.kex.descriptor.descriptor
import org.jetbrains.research.kex.ktype.KexRtManager.rtMapped
import org.jetbrains.research.kex.ktype.asArray
import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.reanimator.actionsequence.ActionList
import org.jetbrains.research.kex.reanimator.actionsequence.ActionSequence
import org.jetbrains.research.kex.reanimator.actionsequence.DefaultConstructorCall
import org.jetbrains.research.kex.reanimator.actionsequence.MethodCall

class KexArrayListGenerator(val fallback: Generator) : Generator {
    override val context: GeneratorContext
        get() = fallback.context
    private val kfgKexArrayList = context.cm.arrayListClass.rtMapped
    private val kexArrayList = kfgKexArrayList.kexType

    override fun supports(descriptor: Descriptor): Boolean =
        descriptor.type == kexArrayList

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence = with(context) {
        descriptor as ObjectDescriptor

        val name = "${descriptor.term}"
        val actionSequence = ActionList(name)
        saveToCache(descriptor, actionSequence)

        val elementData = descriptor["elementData" to cm.type.objectType.kexType.asArray()] as? ArrayDescriptor
        actionSequence += DefaultConstructorCall(kfgKexArrayList)

        if (elementData != null) {
            val addMethod = kfgKexArrayList.getMethod("add", cm.type.boolType, cm.type.objectType)
            for (i in 0 until elementData.length) {
                val element = elementData[i] ?: descriptor { default(elementData.elementType) }
                val elementAS = fallback.generate(element, generationDepth)
                actionSequence += MethodCall(addMethod, listOf(elementAS))
            }
        }

        actionSequence
    }
}