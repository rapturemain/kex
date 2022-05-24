package org.vorpal.research.kex.reanimator.collector

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.util.eq
import org.vorpal.research.kex.util.loadClass
import org.vorpal.research.kex.util.loadKClass
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Field
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.Node
import org.vorpal.research.kfg.visitor.*
import org.vorpal.research.kfg.visitor.pass.AnalysisResult
import org.vorpal.research.kfg.visitor.pass.AnalysisVisitor
import org.vorpal.research.kthelper.assert.asserted
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.`try`
import java.lang.Class
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType
import org.vorpal.research.kfg.ir.Class as KfgClass

class SetterCollector(
    override val cm: ClassManager,
    override val pipeline: Pipeline
) : AnalysisVisitor<SetterAnalysisResult> {

    val ctx get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()

    override fun registerPassDependencies() {
        addRequiredProvider<ExecutionContextProvider>()
    }

    override fun registerAnalysisDependencies() {
        addRequiredAnalysis<MethodFieldAccessCollector>()
    }

    private val KType.kfgType
        get() = when (val jtype = this.javaType) {
            is Class<*> -> ctx.types.get(jtype)
            else -> TODO()
        }

    override fun cleanup() {}

    override fun analyse(node: Node): SetterAnalysisResult {
        require(node is KfgClass) { "Tried to execute analysis on non class" }

        val setters = mutableMapOf<Field, Method>()

        analyseClass(node, setters)

        node.allMethods.forEach {
            analyseMethod(it, setters)
        }

        return SetterAnalysisResult(setters)
    }

    private fun analyseClass(klass: KfgClass, setters: MutableMap<Field, Method>) {
        `try` {
            val kClass = ctx.loader.loadKClass(klass)
            for (property in kClass.memberProperties.filterIsInstance<KMutableProperty<*>>()) {
                for (method in klass.methods) {
                    if (property.setter eq method) {
                        log.info("Method $method is kotlin setter for $property")
                        val field = klass.getField(property.name, property.returnType.kfgType)
                        setters[field] = method
                    }
                }
            }
        }.also {
            if (it.isFailure) log.debug("$klass is not from kotlin")
        }
    }

    private fun analyseMethod(method: Method, setters: MutableMap<Field, Method>) {
        val fieldInstances = `try` {
            val klass = ctx.loader.loadClass(method.klass)
            klass.declaredFields.filter {
                method.name == "set${
                    it.name.replaceFirstChar { character ->
                        if (character.isLowerCase()) character.titlecase(
                            Locale.getDefault()
                        ) else character.toString()
                    }
                }" }
        }.getOrNull() ?: return
        if (fieldInstances.isEmpty()) return
        require(fieldInstances.size == 1)
        val fieldReflection = fieldInstances.first()
        val methodFA = getAnalysis<MethodFieldAccessCollector, MethodFieldAccessResult>(method).fieldAccesses
        if (methodFA.size == 1
            && `try` { fieldReflection.eq(ctx.loader, methodFA.first()) }.getOrElse { false }
            && method.argTypes.size == 1
            && fieldReflection.type.isAssignableFrom(ctx.loader.loadClass(method.argTypes.first()))) {
            log.info("Method $method is java setter for $fieldReflection")
            setters[methodFA.first()] = method
        }
    }
}

data class SetterAnalysisResult(private val setters: Map<Field, Method>) : AnalysisResult {
    fun hasSetter(field: Field) = field in setters
    fun setter(field: Field) = asserted(hasSetter(field)) { setters.getValue(field) }
}