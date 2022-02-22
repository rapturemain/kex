package org.jetbrains.research.kex.state.transformer

import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.basic
import org.jetbrains.research.kex.state.predicate.CallPredicate
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.CallTerm
import org.jetbrains.research.kex.state.term.ConstClassTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.wrap
import org.jetbrains.research.kex.util.*
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.type.ArrayType
import org.jetbrains.research.kfg.type.ClassType
import org.jetbrains.research.kthelper.collection.dequeOf
import java.lang.reflect.Modifier
import org.jetbrains.research.kfg.ir.Class as KfgClass

private val KfgClass.forName get() = this.getMethod("forName", cm.type.classType, cm.type.stringType)
private val KfgClass.forNameWLoader
    get() = this.getMethod(
        "forName",
        cm.type.classType,
        cm.type.stringType,
        cm.type.boolType,
        cm.type.classLoaderType
    )

private val KfgClass.getCanonicalName get() = this.getMethod("getCanonicalName", cm.type.stringType)
private val KfgClass.getClasses get() = this.getMethod("getClasses", cm.type.classType.asArray(cm.type))
private val KfgClass.getComponentType get() = this.getMethod("getComponentType", cm.type.classType)
private val KfgClass.getInterfaces get() = this.getMethod("getInterfaces", cm.type.classType.asArray(cm.type))
private val KfgClass.getModifiers get() = this.getMethod("getModifiers", cm.type.intType)
private val KfgClass.getName get() = this.getMethod("getName", cm.type.stringType)
private val KfgClass.getSuperclass get() = this.getMethod("getSuperclass", cm.type.classType)
private val KfgClass.getTypeName get() = this.getMethod("getTypeName", cm.type.stringType)
private val KfgClass.isAnnotationMethod get() = this.getMethod("isAnnotation", cm.type.boolType)
private val KfgClass.isAssignableFrom get() = this.getMethod("isArray", cm.type.boolType, cm.type.classType)
private val KfgClass.isEnumMethod get() = this.getMethod("isEnum", cm.type.boolType)
private val KfgClass.isInstance get() = this.getMethod("isInstance", cm.type.boolType, cm.type.objectType)
private val KfgClass.isInterfaceMethod get() = this.getMethod("isInterface", cm.type.boolType)
private val KfgClass.isPrimitive get() = this.getMethod("isPrimitive", cm.type.boolType)
private val KfgClass.isSyntheticMethod get() = this.getMethod("isSynthetic", cm.type.boolType)
private val KfgClass.newInstance get() = this.getMethod("newInstance", cm.type.objectType)
private val KfgClass.toString get() = this.getMethod("toString", cm.type.stringType)

class ClassMethodAdapter(val cm: ClassManager) : RecollectingTransformer<ClassMethodAdapter> {
    override val builders = dequeOf(StateBuilder())

    override fun transformCallPredicate(predicate: CallPredicate): Predicate {
        val call = predicate.call as CallTerm
        val args = call.arguments

        val kfgClass = cm.classClass
        if (call.owner.type != kfgClass.kexType) return predicate

        val `this` = call.owner
        val calledMethod = call.method

        currentBuilder += when (calledMethod) {
            kfgClass.forName -> forName(predicate.lhv, args.first())
            kfgClass.forNameWLoader -> forName(predicate.lhv, args.first())
            kfgClass.getCanonicalName -> getCanonicalName(predicate.lhv, `this`)
            kfgClass.getClasses -> if (`this` is ConstClassTerm) getClasses(predicate.lhv, `this`.constantType) else predicate.wrap()
            kfgClass.getComponentType -> if (`this` is ConstClassTerm) getComponentType(predicate.lhv, `this`.constantType) else predicate.wrap()
            kfgClass.getInterfaces -> if (`this` is ConstClassTerm) getInterfaces(predicate.lhv, `this`.constantType) else predicate.wrap()
            kfgClass.getModifiers -> getModifiers(predicate.lhv, `this`)
            kfgClass.getName -> getName(predicate.lhv, `this`)
            kfgClass.getSuperclass -> if (`this` is ConstClassTerm) getSuperclass(predicate.lhv, `this`.constantType) else predicate.wrap()
            kfgClass.getTypeName -> getName(predicate.lhv, `this`)
            kfgClass.isAnnotationMethod -> isAnnotated(predicate.lhv, `this`)
            kfgClass.isEnumMethod -> isEnum(predicate.lhv, `this`)
            kfgClass.isInterfaceMethod -> isInterface(predicate.lhv, `this`)
            kfgClass.isSyntheticMethod -> isSynthetic(predicate.lhv, `this`)
            kfgClass.newInstance -> newInstance(predicate.lhv, `this`)
            kfgClass.toString -> toString(predicate.lhv, `this`)
            else -> predicate.wrap()
        }
        return nothing()
    }

    fun forName(lhv: Term, name: Term) = basic {
        state { lhv.new() }
        val field = generate(KexString())
        state { field equality lhv.field(KexString(), ConstClassTerm.NAME_PROPERTY).load() }
        assume { field equality name }
    }

    fun getCanonicalName(lhv: Term, instance: Term) = basic {
        state { lhv equality instance.field(KexString(), ConstClassTerm.NAME_PROPERTY).load() }
    }

    fun getClasses(lhv: Term, constClass: KexType) = basic {
        val members = when (val kfgType = constClass.getKfgType(cm.type)) {
            is ClassType -> kfgType.klass.allAncestors.flatMap { it.innerClasses.keys }
                .filter { it.isPublic } + kfgType.klass.innerClasses.keys.filter { it.isPublic }
            else -> listOf()
        }
        val length = generate(KexInt())
        state { length equality lhv.length() }
        assume { length equality const (members.size) }
        for ((index, member) in members.withIndex()) {
            val load = generate(KexJavaClass())
            state { load equality lhv[index].load() }
            assume { load equality `class`(member) }
        }
    }

    fun getComponentType(lhv: Term, constClass: KexType) = basic {
        val kfgType = constClass.getKfgType(cm.type)
        state {
            lhv equality if (kfgType is ArrayType) `class`(KexJavaClass(), kfgType.component.kexType) else const(null)
        }
    }

    fun getInterfaces(lhv: Term, constClass: KexType) = basic {
        val interfaces = when (val kfgType = constClass.getKfgType(cm.type)) {
            is ClassType -> if (kfgType.klass.isInterface) listOf(kfgType.klass)
            else kfgType.klass.interfaces
            else -> listOf()
        }
        val length = generate(KexInt())
        state { length equality lhv.length() }
        assume { length equality const (interfaces.size) }
        for ((index, member) in interfaces.withIndex()) {
            val load = generate(KexJavaClass())
            state { load equality lhv[index].load() }
            assume { load equality `class`(member) }
        }
    }

    fun getModifiers(lhv: Term, instance: Term) = basic {
        state {
            lhv equality instance.field(KexInt(), ConstClassTerm.MODIFIERS_PROPERTY).load()
        }
    }

    fun getName(lhv: Term, instance: Term) = basic {
        state {
            lhv equality instance.field(KexString(), ConstClassTerm.NAME_PROPERTY).load()
        }
    }

    fun getSuperclass(lhv: Term, constClass: KexType) = basic {
        val kfgType = constClass.getKfgType(cm.type)
        state {
            lhv equality when (kfgType) {
                is ArrayType -> `class`(cm.objectClass)
                is ClassType -> kfgType.klass.superClass?.let { `class`(it) } ?: const(null)
                else -> const(null)
            }
        }
    }

    fun getTypeName(lhv: Term, instance: Term) = basic {
        state {
            lhv equality instance.field(KexString(), ConstClassTerm.NAME_PROPERTY).load()
        }
    }

    fun isAnnotated(lhv: Term, instance: Term) = basic {
        val modifiers = generate(KexInt())
        val andRes = generate(KexInt())
        state {
            modifiers equality instance.field(KexInt(), ConstClassTerm.MODIFIERS_PROPERTY).load()
        }
        state {
            andRes equality (modifiers and ANNOTATION_MODIFIER)
        }
        state {
            lhv equality (modifiers eq ANNOTATION_MODIFIER)
        }
    }

    fun isEnum(lhv: Term, instance: Term) = basic {
        val modifiers = generate(KexInt())
        val andRes = generate(KexInt())
        state {
            modifiers equality instance.field(KexInt(), ConstClassTerm.MODIFIERS_PROPERTY).load()
        }
        state {
            andRes equality (modifiers and ENUM_MODIFIER)
        }
        state {
            lhv equality (modifiers eq ENUM_MODIFIER)
        }
    }

    fun isInterface(lhv: Term, instance: Term) = basic {
        val modifiers = generate(KexInt())
        val andRes = generate(KexInt())
        state {
            modifiers equality instance.field(KexInt(), ConstClassTerm.MODIFIERS_PROPERTY).load()
        }
        state {
            andRes equality (modifiers and Modifier.INTERFACE)
        }
        state {
            lhv equality (modifiers eq Modifier.INTERFACE)
        }
    }

    fun isSynthetic(lhv: Term, instance: Term) = basic {
        val modifiers = generate(KexInt())
        val andRes = generate(KexInt())
        state {
            modifiers equality instance.field(KexInt(), ConstClassTerm.MODIFIERS_PROPERTY).load()
        }
        state {
            andRes equality (modifiers and SYNTHETIC_MODIFIER)
        }
        state {
            lhv equality (modifiers eq SYNTHETIC_MODIFIER)
        }
    }

    fun newInstance(lhv: Term, instance: Term) = basic {
        state {
            lhv.new()
        }
        val klass = generate(KexJavaClass())
        state { klass equality lhv.klass }
        assume { klass equality instance }
    }

    fun toString(lhv: Term, instance: Term) = basic {
        state {
            lhv equality instance.field(KexString(), ConstClassTerm.NAME_PROPERTY).load()
        }
    }
}