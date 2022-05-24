package org.vorpal.research.kex.reanimator

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.parameters.concreteParameters
import org.vorpal.research.kex.random.GenerationException
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConcolicSequenceGenerator
import org.vorpal.research.kex.reanimator.codegen.ExecutorTestCasePrinter
import org.vorpal.research.kex.reanimator.codegen.packageName
import org.vorpal.research.kex.reanimator.collector.SetterAnalysisResult
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.transformer.generateFinalDescriptors
import org.vorpal.research.kex.state.transformer.generateInputByModel
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.pipelineStub
import org.vorpal.research.kthelper.logging.log
import java.nio.file.Path

private val visibilityLevel by lazy {
    kexConfig.getEnumValue("testGen", "visibility", true, Visibility.PUBLIC)
}

class UnsafeGenerator(
    override val ctx: ExecutionContext,
    val setters: SetterAnalysisResult,
    val method: Method,
    val testName: String
) : ParameterGenerator {
    private val asGenerator = ConcolicSequenceGenerator(ctx, PredicateStateAnalysis(ctx.cm, pipelineStub()), setters, visibilityLevel)
    private val printer = ExecutorTestCasePrinter(ctx, method.packageName, testName)
    val testKlassName = printer.fullKlassName

    fun generate(descriptors: Parameters<Descriptor>) = try {
        val sequences = descriptors.actionSequences
        printer.print(method, sequences.rtUnmapped)
    } catch (e: GenerationException) {
        throw e
    } catch (e: Exception) {
        throw GenerationException(e)
    } catch (e: Error) {
        throw GenerationException(e)
    }

    fun generate(state: PredicateState, model: SMTModel) {
        val descriptors = generateFinalDescriptors(method, ctx, model, state).concreteParameters(ctx.cm)
        log.debug("Generated descriptors:\n$descriptors")
        generate(descriptors)
    }

    override fun generate(
        testName: String,
        method: Method,
        state: PredicateState,
        model: SMTModel
    ): Parameters<Any?> = try {
        val descriptors = generateFinalDescriptors(method, ctx, model, state).concreteParameters(ctx.cm)
        log.debug("Generated descriptors:\n$descriptors")
        val sequences = descriptors.actionSequences
        printer.print(testName, method, sequences.rtUnmapped)
        generateInputByModel(ctx, method, state, model)
    } catch (e: GenerationException) {
        throw e
    } catch (e: Exception) {
        throw GenerationException(e)
    } catch (e: Error) {
        throw GenerationException(e)
    }


    override fun emit(): Path {
        printer.emit()
        return printer.targetFile.toPath()
    }

    val Descriptor.actionSequence: ActionSequence
        get() = asGenerator.generate(this)

    private val Parameters<Descriptor>.actionSequences: Parameters<ActionSequence>
        get() {
            val thisSequence = instance?.actionSequence
            val argSequences = arguments.map { it.actionSequence }
            val staticFields = statics.map { it.actionSequence }.toSet()
            return Parameters(thisSequence, argSequences, staticFields)
        }
}
