package org.vorpal.research.kex.reanimator.actionsequence.generator

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.collector.SetterAnalysisResult
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log

class ConcolicSequenceGenerator(override val context: GeneratorContext) : Generator {
    private val typeGenerators = mutableSetOf<Generator>()
    private var searchDepth = 0

    override fun supports(descriptor: Descriptor) = true


    init {
        typeGenerators += ConstantGenerator(context)
        typeGenerators += CharsetGenerator(this)
        typeGenerators += StringGenerator(this)
        typeGenerators += ClassGenerator(this)
        typeGenerators += FieldGenerator(this)
        typeGenerators += ReflectionEnumGenerator(this)
        typeGenerators += KexRtGenerator(this)
        typeGenerators += UnknownGenerator(this)
    }

    private val Descriptor.wrappedType get() = when (this.type) {
        is KexNull -> context.types.objectType
        else -> this.type.getKfgType(context.types)
    }

    constructor(executionCtx: ExecutionContext, psa: PredicateStateAnalysis, setters: SetterAnalysisResult, visibilityLevel: Visibility)
            : this(GeneratorContext(executionCtx, psa, setters, visibilityLevel))

    val Descriptor.generator: Generator
        get() = typeGenerators.firstOrNull { it.supports(this) } ?: unreachable {
            log.error("Could not find a generator for $this")
        }

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence = with(context) {
        getFromCache(descriptor)?.let { return it }
        searchDepth++

        val typeGenerator = descriptor.generator

        return typeGenerator.generate(descriptor, generationDepth + 1)
    }
}