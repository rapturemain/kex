package org.jetbrains.research.kex.random

import org.jeasy.random.EasyRandom
import org.jeasy.random.EasyRandomParameters
import org.jeasy.random.ObjectCreationException
import org.jeasy.random.api.ObjectFactory
import org.jeasy.random.api.RandomizerContext
import org.jeasy.random.util.CollectionUtils.randomElementOf
import org.jeasy.random.util.ReflectionUtils.getPublicConcreteSubTypesOf
import org.jeasy.random.util.ReflectionUtils.isAbstract
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.util.log
import org.jetbrains.research.kex.util.tryOrNull
import org.jetbrains.research.kfg.Package
import org.objenesis.ObjenesisStd
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

class EasyRandomDriver(val config: BeansConfig = defaultConfig) : Randomizer {
    companion object {

        data class BeansConfig(
                val depth: Int,
                val collectionSize: IntRange,
                val stringLength: IntRange,
                val attempts: Int,
                val excludes: Set<Package>
        )

        val defaultConfig: BeansConfig by lazy {
            val depth = kexConfig.getIntValue("easy-random", "depth", 10)
            val minCollectionSize = kexConfig.getIntValue("easy-random", "minCollectionSize", 0)
            val maxCollectionSize = kexConfig.getIntValue("easy-random", "maxCollectionSize", 1000)
            val minStringLength = kexConfig.getIntValue("easy-random", "minStringLength", 0)
            val maxStringLength = kexConfig.getIntValue("easy-random", "maxStringLength", 1000)
            val attempts = kexConfig.getIntValue("easy-random", "generationAttempts", 1)
            val excludes = kexConfig.getMultipleStringValue("easy-random", "exclude").map { Package.parse(it) }.toSet()
            BeansConfig(
                    depth = depth,
                    collectionSize = minCollectionSize..maxCollectionSize,
                    stringLength = minStringLength..maxStringLength,
                    attempts = attempts,
                    excludes = excludes
            )
        }
    }

    private class KexObjectFactory : ObjectFactory {
        private val objenesis = ObjenesisStd(false)

        override fun <T> createInstance(type: Class<T>, context: RandomizerContext): T =
                when {
                    context.parameters.isScanClasspathForConcreteTypes && isAbstract<T>(type) -> {
                        val randomConcreteSubType = randomElementOf<Class<*>>(getPublicConcreteSubTypesOf<T>(type))
                        if (randomConcreteSubType == null) {
                            throw InstantiationError("Unable to find a matching concrete subtype of type: $type in the classpath")
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            createNewInstance(randomConcreteSubType) as T
                        }
                    }
                    else -> try {
                        createNewInstance(type)
                    } catch (e: Error) {
                        throw ObjectCreationException("Unable to create an instance of type: $type", e)
                    }
                }

        private fun <T> createNewInstance(type: Class<T>): T = try {
            val noArgConstructor = type.getDeclaredConstructor()
            if (!noArgConstructor.isAccessible) {
                noArgConstructor.isAccessible = true
            }
            noArgConstructor.newInstance()
        } catch (exception: Exception) {
            objenesis.newInstance(type)
        }
    }

    private val randomizer = EasyRandom(
            EasyRandomParameters()
                    .randomizationDepth(config.depth)
                    .collectionSizeRange(config.collectionSize.first, config.collectionSize.last)
                    .stringLengthRange(config.stringLength.last, config.stringLength.last)
                    .scanClasspathForConcreteTypes(true)
                    .excludeType { type -> config.excludes.any { it.isParent(Package.parse(type.name)) } }
                    .objectFactory(KexObjectFactory())
    )

    private fun <T> generateClass(klass: Class<T>) = randomizer.nextObject(klass)

    private fun generateParameterized(type: ParameterizedType): Any? {
        val rawType = type.rawType
        val `object` = next(rawType)
        when (rawType) {
            is Class<*> -> {
                val typeParams = rawType.typeParameters.zip(type.actualTypeArguments).toMap()
                for (it in rawType.declaredFields) {
                    val genType = typeParams[it.genericType as? TypeVariable<*>] ?: it.genericType
                    it.isAccessible = true
                    val value = next(genType)
                    it.set(`object`, value)
                }
            }
            else -> throw UnknownTypeException(type.toString())
        }
        return `object`
    }

    private fun generateTypeVariable(type: TypeVariable<*>): Any? {
        val bounds = type.bounds
        require(bounds.size == 1) { log.debug("Unexpected size of type variable bounds: ${bounds.map { it.typeName }}") }
        return next(bounds.first())
    }

    override fun next(type: Type): Any? {
        repeat(config.attempts) {
            tryOrNull {
                return when (type) {
                    is Class<*> -> generateClass(type)
                    is ParameterizedType -> generateParameterized(type)
                    is TypeVariable<*> -> generateTypeVariable(type)
                    else -> throw UnknownTypeException(type.toString())
                }
            }
        }
        throw GenerationException("Unable to next a random instance of type $type")
    }
}