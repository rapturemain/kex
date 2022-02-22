package org.jetbrains.research.kex.reanimator.actionsequence.generator

import org.jetbrains.research.kex.descriptor.*
import org.jetbrains.research.kex.ktype.KexChar
import org.jetbrains.research.kex.ktype.KexString
import org.jetbrains.research.kex.ktype.asArray
import org.jetbrains.research.kex.ktype.type
import org.jetbrains.research.kex.reanimator.actionsequence.ActionList
import org.jetbrains.research.kex.reanimator.actionsequence.ActionSequence
import org.jetbrains.research.kex.reanimator.actionsequence.StaticFieldGetter
import org.jetbrains.research.kthelper.logging.log

class CharsetGenerator(private val fallback: Generator) : Generator {
    companion object {
        private const val CHARSET_CLASS = "java/nio/charset/Charset"
        private const val CHARSETS_CLASS = "java/nio/charset/StandardCharsets"
        private const val DEFAULT_CHARSET = "US_ASCII"
        private val existingCharsets = setOf("US_ASCII", "ISO_8859_1", "UTF_8", "UTF_16BE", "UTF_16LE", "UTF_16")
    }
    override val context: GeneratorContext
        get() = fallback.context

    override fun supports(descriptor: Descriptor) = descriptor.type.toString() == CHARSET_CLASS

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence = with(context) {
        descriptor as? ObjectDescriptor ?: throw IllegalArgumentException()
        descriptor.reduce()

        val name = "${descriptor.term}"
        val actionSequence = ActionList(name)
        saveToCache(descriptor, actionSequence)

        val charsetClass = context.cm[CHARSET_CLASS]
        val charsetsClass = context.cm[CHARSETS_CLASS]

        val nameDescriptor = descriptor["name", KexString()] as? ObjectDescriptor
        val actualName = nameDescriptor?.let { obj ->
            val valueDescriptor = obj["value", KexChar().asArray()] as? ArrayDescriptor
            valueDescriptor?.let { array ->
                (0 until array.length).map {
                    (array.elements.getOrDefault(it, descriptor { const(' ') }) as ConstantDescriptor.Char).value
                }.joinToString("")
            }
        } ?: DEFAULT_CHARSET
        actionSequence += if (actualName in existingCharsets) {
            StaticFieldGetter(charsetsClass.getField(actualName, charsetClass.type))
        } else {
            log.warn("Could not generate charset with name $actualName, falling back to default $DEFAULT_CHARSET")
            StaticFieldGetter(charsetsClass.getField(DEFAULT_CHARSET, charsetClass.type))
        }
        return actionSequence
    }
}