package org.vorpal.research.kex.asm.manager

import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.asm.util.visibility
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.trace.AbstractTrace
import org.vorpal.research.kex.trace.TraceManager
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.addRequiredPass
import org.vorpal.research.kthelper.logging.log
import java.util.*

private val visibilityLevel by lazy {
    kexConfig.getEnumValue("testGen", "visibility", true, Visibility.PUBLIC)
}

val Method.isImpactable: Boolean
    get() = when {
        this.isAbstract -> false
        this.isStaticInitializer -> false
        this.klass.isSynthetic -> false
        this.klass.isAbstract && this.isConstructor -> false
        this.isSynthetic -> false
        visibilityLevel > this.klass.visibility -> false
        visibilityLevel > this.visibility -> false
        else -> true
    }

data class CoverageInfo(
    val bodyCovered: Int,
    val bodyTotal: Int,
    val fullCovered: Int,
    val fullTotal: Int,
) {
    val bodyCoverage: Double get() = (bodyCovered * 100).toDouble() / bodyTotal
    val fullCoverage: Double get() = (fullCovered * 100).toDouble() / fullTotal

    constructor() : this(0, 0, 0, 0)

    operator fun plus(other: CoverageInfo): CoverageInfo {
        return CoverageInfo(
            this.bodyCovered + other.bodyCovered,
            this.bodyTotal + other.bodyTotal,
            this.fullCovered + other.fullCovered,
            this.fullTotal + other.fullTotal
        )
    }
}

class CoverageCounter<T : AbstractTrace> private constructor(
    override val cm: ClassManager,
    override val pipeline: Pipeline,
    private val tm: TraceManager<T>,
    val methodFilter: (Method) -> Boolean
) : MethodVisitor {
    private val methodInfos = hashMapOf<Method, CoverageInfo>()

    constructor(cm: ClassManager, pipeline: Pipeline, tm: TraceManager<T>) :
            this(cm, pipeline, tm, { true })
    constructor(cm: ClassManager, pipeline: Pipeline, tm: TraceManager<T>, pkg: Package) :
            this(cm, pipeline, tm, { pkg.isParent(it.klass.pkg) })

    constructor(cm: ClassManager, pipeline: Pipeline, tm: TraceManager<T>, klass: Class) :
            this(cm, pipeline, tm, { it.klass == klass })

    constructor(cm: ClassManager, pipeline: Pipeline, tm: TraceManager<T>, methods: Set<Method>) :
            this(cm, pipeline, tm, { it in methods })

    val totalCoverage: CoverageInfo
        get() = methodInfos.values.fold(CoverageInfo()) { acc, coverageInfo ->
            acc + coverageInfo
        }

    override fun registerPassDependencies() {
        addRequiredPass<MethodWrapperInitializer>()
    }

    private val Method.isInteresting: Boolean
        get() = when {
            this.isAbstract -> false
            this.isStaticInitializer -> false
            this.klass.isSynthetic -> false
            this.isSynthetic -> false
            !this.hasBody -> false
            else -> true
        }

    override fun cleanup() {}

    override fun visit(method: Method) {
        if (!method.isInteresting) return
        if (!methodFilter(method)) return

        val bodyBlocks = method.bodyBlocks.filter { it.wrapper != null }.groupBy { it.wrapper!! }
        val catchBlocks = method.catchBlocks.filter { it.wrapper != null }.groupBy { it.wrapper!! }
        val bodyCovered = bodyBlocks.count { (_, blocks) -> blocks.any { tm.isCovered(it) } }
        val catchCovered = catchBlocks.count { (_, blocks) -> blocks.any { tm.isCovered(it) } }

        val info = CoverageInfo(
            bodyCovered, bodyBlocks.size,
            bodyCovered + catchCovered, bodyBlocks.size + catchBlocks.size
        )
        methodInfos[method] = info

        log.info(
            "Method $method coverage: " +
                    "body = ${String.format(Locale.ENGLISH, "%.2f", info.bodyCoverage)}; " +
                    "full = ${String.format(Locale.ENGLISH, "%.2f", info.fullCoverage)}"
        )
    }
}
