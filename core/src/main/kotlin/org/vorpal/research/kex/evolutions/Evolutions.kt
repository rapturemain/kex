package org.vorpal.research.kex.evolutions

import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.analysis.LoopSimplifier
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.*
import org.vorpal.research.kfg.ir.value.instruction.*
import org.vorpal.research.kfg.visitor.Loop
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.addRequiredPass
import ru.spbstu.*
import ru.spbstu.wheels.mapToArray

/**
 * Evaluates evolutions in given symbolic expression.
 * @param s given symbolic expression.
 * @param iterations map from loop to it's induction variable.
 */
fun evaluateEvolutions(s: Symbolic, iterations: MutableMap<Loop, Var>): Symbolic = s.transform { sym ->
    when (sym) {
        is Evolution -> {
            val lhv = transform(sym.lhv)
            val rhv = transform(sym.rhv)
            val variable = iterations.getOrPut(sym.loop, defaultVar())
            when (sym.opcode) {
                EvoOpcode.PLUS -> lhv + RowSum.of(variable, Const(1), variable, rhv) - rhv
                EvoOpcode.TIMES -> lhv * RowProduct.of(variable, Const(1), variable, rhv)
            }
        }
        else -> transform(sym)
    }
}

fun defaultVar(): () -> Var = { Var.fresh("iteration") }


/**
 * Checks if symbolic expression contains given variable.
 * @param v given variable.
 */
fun Symbolic.hasVar(v: Var): Boolean = when (this) {
    is Const -> false
    is Var -> v == this
    is Apply -> arguments.any { it.hasVar(v) }
    is Product -> parts.any { it.key.hasVar(v) }
    is Sum -> parts.any { it.key.hasVar(v) }
}

fun Symbolic.transform(body: TransformScope.(Symbolic) -> Symbolic): Symbolic {
    return TransformScope(body).body(this)
}

/**
 * Returns reciprocal of symbolic expression.
 */
fun Symbolic.reciprocal(): Symbolic {
    return Const(1) / this
}

/**
 * Breaks sum in two parts using given predicate.
 */
inline fun Sum.partition(predicate: (Symbolic) -> Boolean): Pair<Symbolic, Symbolic> {
    val (c1, c2) = this.parts.partitionTo(mutableMapOf(), mutableMapOf()) { k, _ -> predicate(k) }
    return Sum(parts = c1).simplify() to Sum(parts = c2, constant = this.constant).simplify()
}

class TransformScope(val body: TransformScope.(Symbolic) -> Symbolic) {
    fun transform(s: Symbolic): Symbolic =
        when (s) {
            is Const, is Var -> s
            is Apply -> s.copy(arguments = s.arguments.map { body(it) })
            is Product -> Product.of(Const(s.constant), *s.parts.mapToArray { (s, c) -> body(s) pow c })
            is Sum -> Sum.of(Const(s.constant), *s.parts.mapToArray { (s, c) -> body(s) * c })
        }
}

/**
 * Visitor that performs evaluation of all evolutions in CFG.
 */
open class Evolutions(override val cm: ClassManager, override val pipeline: Pipeline) : MethodVisitor {
    protected val inst2loop = mutableMapOf<Instruction, Loop>()
    protected val loopPhis = mutableMapOf<PhiInst, Loop>()
    protected val inst2var = mutableMapOf<Value, Var>()
    protected val var2inst = mutableMapOf<Var, Value>()

    private val PhiInst.loop get() = loopPhis.getValue(this)
    private val PhiInst.loopValue get() = this.incomings.getValue(this.loop.latch)
    private val PhiInst.baseValue get() = this.incomings.getValue(this.loop.preheader)


    private operator fun Loop.contains(inst: Instruction) = inst.parent in this

    private val equations = mutableMapOf<Value, Symbolic?>()

    /**
     * Transforms given value to it's symbolic form.
     * Using cache to avoid unnecessary evaluations.
     * Corresponds to AnalyzeEvolution from the paper.
     * @param v given value.
     * @return symbolic representation of the value.
     */
    protected fun transform(v: Value): Symbolic = when (v) {
        in equations -> equations[v]!!
        is IntConstant -> Const(v.value)
        is LongConstant -> Const(v.value)
        is ShortConstant -> Const(v.value.toInt())
        is ByteConstant -> Const(v.value.toInt())
        is UnaryInst -> when (v.opcode) {
            UnaryOpcode.NEG -> -transform(v.operand)
            UnaryOpcode.LENGTH -> Apply("\\length", transform(v.operand))
        }
        is BinaryInst -> when (v.opcode) {
            BinaryOpcode.ADD -> transform(v.lhv) + transform(v.rhv)
            BinaryOpcode.SUB -> transform(v.lhv) - transform(v.rhv)
            BinaryOpcode.MUL -> transform(v.lhv) * transform(v.rhv)
            BinaryOpcode.DIV -> transform(v.lhv) / transform(v.rhv)
            BinaryOpcode.SHL -> transform(v.lhv) shl transform(v.rhv)
            BinaryOpcode.SHR -> transform(v.lhv) shr transform(v.rhv)
            else -> KFGBinary(v.opcode, transform(v.lhv), transform(v.rhv))
        }
        else -> {
            if (v.isNameDefined) {
                val res = Var(v.name.toString())
                var2inst[res] = v
                inst2var[v] = res
                res
            } else Undefined
        }
    }.also { equations[v] = it }

    private val phiEquations: MutableMap<PhiInst, Symbolic> = mutableMapOf()

    /**
     * Builds evolution for given phi instance.
     * Uses caching to avoid unnecessary recalculations.
     * @param v given phi instance.
     * @return symbolic formula representing evolution of given phi in the loop.
     */
    protected fun buildPhiEquation(v: PhiInst): Symbolic {
        if (v in phiEquations) return phiEquations[v]!!
        phiEquations[v] = Undefined
        phiEquations[v] = buildPhiEquationUncached(v)
        return phiEquations[v]!!
    }

    /**
     * Performs depth-first search on CFG graph from given phi instance,
     * search stops when this phi instance met again. Resulting path used
     * to compute recurrence relation of phi variable.
     * Some heuristics used to simplify recurrence relation and turn it into
     * function from iteration number.
     * @param v given phi instance.
     * @return symbolic representation of recurrence relation, possibly simplified.
     */
    private fun buildPhiEquationUncached(v: PhiInst): Symbolic {
        val base = transform(v.baseValue)
        var recur = transform(v.loopValue)
        val deps = recur.vars()
        for (dep in deps) {
            val dvar = var2inst[dep]!!
            if (dvar != v && dvar is PhiInst && dvar in loopPhis && dvar.loop == v.loop) {
                recur = recur.subst(dep to buildPhiEquation(dvar))
            }
        }
        val me = transform(v) as Var

        when (recur) {
            is Const, is Var -> return recur
            is ShiftLeft -> {
                val (lhv, rhv) = recur.arguments
                if (lhv != me) return Undefined
                return ShiftLeft(
                    base,
                    Evolution(v.loop, EvoOpcode.PLUS, Const.ZERO, rhv)
                )
            }
            is ShiftRight -> {
                val (lhv, rhv) = recur.arguments
                if (lhv != me) return Undefined
                return ShiftRight(
                    base,
                    Evolution(v.loop, EvoOpcode.PLUS, Const.ZERO, rhv)
                )
            }
            is KFGBinary -> {
                val (lhv, rhv) = recur.arguments
                if (lhv != me) return Undefined

                return when (recur.opcode) {
                    BinaryOpcode.DIV -> KFGBinary(
                        BinaryOpcode.DIV,
                        base,
                        Evolution(v.loop, EvoOpcode.TIMES, Const.ONE, rhv)
                    )
                    BinaryOpcode.USHR ->
                        KFGBinary(
                            recur.opcode,
                            base,
                            Evolution(v.loop, EvoOpcode.PLUS, Const.ZERO, rhv)
                        )
                    else -> Undefined
                }
            }
            is Apply -> return recur
            is Product -> {
                // decompose recur to (Alpha * me)
                val alpha = recur / me
                if (alpha.hasVar(me)) return Undefined

                return Evolution(v.loop, EvoOpcode.TIMES, base, alpha)
            }
            is Sum -> {
                // decompose recur to (Alpha * me + Beta)
                val (l, beta) = recur.partition { it.hasVar(me) }
                val alpha = l / me
                if (alpha.hasVar(me)) return Undefined
                return when {
                    alpha == Const.ONE -> Evolution(v.loop, EvoOpcode.PLUS, base, beta)
                    beta == Const.ZERO -> Evolution(v.loop, EvoOpcode.TIMES, base, alpha)
                    alpha == Const.ZERO -> Evolution(v.loop, EvoOpcode.PLUS, Const.ZERO, beta)
                    else -> Evolution(
                        v.loop,
                        EvoOpcode.TIMES,
                        Evolution(
                            v.loop,
                            EvoOpcode.PLUS,
                            base,
                            Evolution(v.loop, EvoOpcode.TIMES, beta, alpha.reciprocal())
                        ),
                        alpha
                    )
                }
            }
        }
    }

    override fun registerPassDependencies() {
        addRequiredPass<LoopSimplifier>()
    }

    override fun cleanup() {}
}

/**
 * Collects all loops in the method.
 * @param method given method.
 * @return sequence of all loops in the method.
 */
fun walkLoops(method: Method) = sequence {
    val topLevel = method.getLoopInfo()
    for (loop in topLevel) yieldAll(walkLoops(loop))
}

/**
 * Recursively collects all sub-loops in the given loop.
 * @param top given top loop.
 * @return sequence of all sub-loops of the top loop including top loop itself collected recursively.
 */
fun walkLoops(top: Loop): Sequence<Loop> = sequence {
    for (loop in top.subLoops) {
        yieldAll(walkLoops(loop))
    }
    yield(top)
}