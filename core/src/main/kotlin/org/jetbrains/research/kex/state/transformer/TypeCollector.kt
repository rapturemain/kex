package org.jetbrains.research.kex.state.transformer

import org.jetbrains.research.kex.ktype.KexJavaClass
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.util.parseAsConcreteType
import org.jetbrains.research.kfg.type.TypeFactory

private class ClassAccessDetector : Transformer<ClassAccessDetector> {
    var hasClassAccess = false
        private set

    override fun transformTerm(term: Term): Term {
        if (term.type == KexJavaClass()) hasClassAccess = true
        return super.transformTerm(term)
    }

    override fun transformClassAccessTerm(term: ClassAccessTerm): Term {
        hasClassAccess = true
        return super.transformClassAccessTerm(term)
    }
}

fun hasClassAccesses(ps: PredicateState) = ClassAccessDetector().let {
    it.apply(ps)
    it.hasClassAccess
}

class TypeCollector(val tf: TypeFactory, val checkStringTypes: Boolean = false) : Transformer<TypeCollector> {
    val types = mutableSetOf<KexType>()

    override fun apply(ps: PredicateState): PredicateState {
        val res = super.apply(ps)
        getConstStringMap(ps).keys.forEach {
            handleStringType(it)
        }
        return res
    }

    override fun transformTerm(term: Term): Term {
        types += term.type
        return super.transformTerm(term)
    }

    override fun transformInstanceOf(term: InstanceOfTerm): Term {
        types += term.checkedType
        return super.transformInstanceOf(term)
    }

    override fun transformConstClass(term: ConstClassTerm): Term {
        types += term.constantType
        return super.transformConstClass(term)
    }

    override fun transformConstString(term: ConstStringTerm): Term {
        handleStringType(term.value)
        return super.transformConstString(term)
    }

    private fun handleStringType(string: String) {
        if (checkStringTypes) {
            parseAsConcreteType(tf, string)?.let {
                types += it
            }
        }
    }
}

fun collectTypes(tf: TypeFactory, ps: PredicateState): Set<KexType> {
    val tc = TypeCollector(tf, hasClassAccesses(ps))
    tc.apply(ps)
    return tc.types
}