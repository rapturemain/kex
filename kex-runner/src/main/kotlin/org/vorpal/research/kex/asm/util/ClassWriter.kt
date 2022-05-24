package org.vorpal.research.kex.asm.util

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.asm.transform.RuntimeTraceCollector
import org.vorpal.research.kex.asm.transform.SymbolicTraceCollector
import org.vorpal.research.kex.asm.transform.SystemExitTransformer
import org.vorpal.research.kex.asm.transform.TraceInstrumenter
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.util.write
import org.vorpal.research.kfg.visitor.*
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.tryOrNull
import java.nio.file.Path
import java.nio.file.Paths

class ClassWriter(
    override val cm: ClassManager,
    override val pipeline: Pipeline,
    val target: Path
) : ClassVisitor {

    val ctx get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()

    override fun cleanup() {}

    override fun registerPassDependencies() {
        addRequiredProvider<ExecutionContextProvider>()

        addSoftDependencyPass<SystemExitTransformer>()
        addSoftDependencyPass<RuntimeTraceCollector>()
        addSoftDependencyPass<TraceInstrumenter>()
        addSoftDependencyPass<SymbolicTraceCollector>()
    }

    override fun visit(klass: Class) {
        val classFileName = target.resolve(Paths.get(klass.pkg.fileSystemPath, "${klass.name}.class")).toAbsolutePath()
        tryOrNull {
            klass.write(cm, ctx.loader, classFileName)
        } ?: log.warn("Could not write class $klass")
    }
}
