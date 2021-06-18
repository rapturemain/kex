package org.jetbrains.research.kex.serialization

import kotlinx.serialization.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.research.kex.descriptor.*
import org.jetbrains.research.kex.ktype.KexArray
import org.jetbrains.research.kex.ktype.KexClass
import org.jetbrains.research.kex.ktype.KexType

private typealias Id = String

@Serializable
private sealed class DescriptorWrapper() {
    abstract val id: Id
    abstract val type: KexType

    fun toDescriptor(map: Map<Id, DescriptorWrapper>, context: MutableMap<Id, Descriptor> = mutableMapOf()): Descriptor {
        if (id in context) return context.getValue(id)
        convert(map, context)
        return context.getValue(id)
    }
    protected abstract fun convert(map: Map<Id, DescriptorWrapper>, output: MutableMap<Id, Descriptor>)

    @Serializable
    class Constant(
        override val id: Id,
        override val type: KexType,
        val value: String
    ) : DescriptorWrapper() {
        override fun convert(map: Map<Id, DescriptorWrapper>, output: MutableMap<Id, Descriptor>) {
            if (id in output) return
            output[id] = descriptor { const(type, value) }
        }
    }

    @Serializable
    class Object(
        override val id: Id,
        override val type: KexType,
        val fields: MutableList<Pair<Pair<String, KexType>, Id>>
    ) : DescriptorWrapper() {
        override fun convert(map: Map<Id, DescriptorWrapper>, output: MutableMap<Id, Descriptor>) {
            if (id in output) return
            val instance = descriptor { `object`(type as KexClass) }.also {
                output[id] = it
            } as FieldContainingDescriptor<*>
            for ((field, fieldId) in fields) {
                map.getValue(fieldId).toDescriptor(map, output)
                instance[field] = output[fieldId]!!
            }
        }
    }

    @Serializable
    class Klass(
        override val id: Id,
        override val type: KexType,
        val fields: MutableList<Pair<Pair<String, KexType>, Id>>
    ) : DescriptorWrapper() {
        override fun convert(map: Map<Id, DescriptorWrapper>, output: MutableMap<Id, Descriptor>) {
            if (id in output) return
            val instance = descriptor { `object`(type as KexClass) }.also {
                output[id] = it
            } as FieldContainingDescriptor<*>
            for ((field, fieldId) in fields) {
                map.getValue(fieldId).toDescriptor(map, output)
                instance[field] = output[fieldId]!!
            }
        }
    }

    @Serializable
    class Array(
        override val id: Id,
        override val type: KexType,
        val length: Int,
        val elements: MutableMap<Int, Id>
    ) : DescriptorWrapper() {
        override fun convert(map: Map<Id, DescriptorWrapper>, output: MutableMap<Id, Descriptor>) {
            if (id in output) return
            val array = descriptor { array(length, (type as KexArray).element) }.also {
                output[id] = it
            } as ArrayDescriptor
            for ((index, elementId) in elements) {
                map.getValue(elementId).toDescriptor(map, output)
                array[index] = output[elementId]!!
            }
        }
    }

    override fun hashCode() = this.id.hashCode()

    override fun equals(other: Any?): Boolean {
        return this.id == (other as? DescriptorWrapper)?.id
    }
}

private val Descriptor.id get() = "$term"

private fun Descriptor.toWrapper(): Map<Id, DescriptorWrapper> {
    val map = mutableMapOf<Id, DescriptorWrapper>()
    this.toWrapper(map)
    return map
}

private fun Descriptor.toWrapper(visited: MutableMap<Id, DescriptorWrapper>) {
    val id = this.id
    if (id in visited) return
    when (this) {
        ConstantDescriptor.Null -> visited[id] = DescriptorWrapper.Constant(id, this.type, "null")
        is ConstantDescriptor.Bool -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Byte -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Char -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Short -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Int -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Long -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Float -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ConstantDescriptor.Double -> visited[id] = DescriptorWrapper.Constant(id, this.type, "${this.value}")
        is ArrayDescriptor -> {
            val array = DescriptorWrapper.Array(id, this.type, this.length, mutableMapOf()).also {
                visited[id] = it
            }
            for ((index, element) in this.elements) {
                array.elements[index] = element.id
            }
            for (element in this.elements.values) {
                element.toWrapper(visited)
            }
        }
        is ClassDescriptor -> {
            val klass = DescriptorWrapper.Klass(id, this.type, mutableListOf()).also {
                visited[id] = it
            }
            for ((field, value) in this.fields) {
                klass.fields += field to value.id
            }
            for (field in this.fields.values) {
                field.toWrapper(visited)
            }
        }
        is ObjectDescriptor -> {
            val instance = DescriptorWrapper.Klass(id, this.type, mutableListOf()).also {
                visited[id] = it
            }
            for ((field, value) in this.fields) {
                instance.fields += field to value.id
            }
            for (field in this.fields.values) {
                field.toWrapper(visited)
            }
        }
    }
}

@ExperimentalSerializationApi
@Serializer(forClass = Descriptor::class)
internal object DescriptorSerializer : KSerializer<Descriptor> {
    private val context = mutableMapOf<Id, Descriptor>()
    private val idSerializer = String.serializer()
    private val wrapperSerializer = MapSerializer(idSerializer, DescriptorWrapper.serializer())
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("descriptor") {
            element<Id>("id")
            element("wrappers", wrapperSerializer.descriptor)
        }

    override fun serialize(encoder: Encoder, value: Descriptor) {
        val wrappers = value.toWrapper()
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(descriptor, 0, idSerializer, value.id)
        output.encodeSerializableElement(descriptor, 1, wrapperSerializer, wrappers)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): Descriptor {
        val input = decoder.beginStructure(descriptor)
        lateinit var id: Id
        lateinit var wrappers: Map<Id, DescriptorWrapper>
        loop@ while (true) {
            when (val i = input.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> id = input.decodeSerializableElement(idSerializer.descriptor, i, idSerializer)
                1 -> wrappers = input.decodeSerializableElement(wrapperSerializer.descriptor, i, wrapperSerializer)
                else -> throw SerializationException("Unknown index $i")
            }
        }
        input.endStructure(descriptor)
        val wrapper = wrappers.getValue(id)
        return wrapper.toDescriptor(wrappers, context)
    }
}

@ExperimentalSerializationApi
internal inline fun <reified T : Descriptor> DescriptorSerializer.to() = object : KSerializer<T> {
    override val descriptor get() = this@to.descriptor

    override fun deserialize(decoder: Decoder): T = this@to.deserialize(decoder) as T

    override fun serialize(encoder: Encoder, value: T) {
        this@to.serialize(encoder, value)
    }
}