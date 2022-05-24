package org.vorpal.research.kex.asm.transform

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ExecutionContextProvider
import org.vorpal.research.kex.trace.symbolic.InstructionTraceCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy
import org.vorpal.research.kex.util.insertAfter
import org.vorpal.research.kex.util.insertBefore
import org.vorpal.research.kex.util.wrapValue
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.MethodDesc
import org.vorpal.research.kfg.ir.value.*
import org.vorpal.research.kfg.ir.value.instruction.*
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.Pipeline
import org.vorpal.research.kfg.visitor.addRequiredProvider
import org.vorpal.research.kfg.visitor.getProvider
import org.vorpal.research.kthelper.collection.MutableBuilder
import org.vorpal.research.kthelper.collection.buildList

class SymbolicTraceCollector(
    override val cm: ClassManager,
    override val pipeline: Pipeline,
    val ignores: Set<Package> = setOf()
) : MethodVisitor, InstructionBuilder {
    val executionContext get() = getProvider<ExecutionContextProvider, ExecutionContext>().provide()
    override val ctx: UsageContext = EmptyUsageContext

    override val instructions: InstructionFactory
        get() = cm.instruction
    override val types: TypeFactory
        get() = cm.type
    override val values: ValueFactory
        get() = cm.value

    private val collectorClass = cm[InstructionTraceCollector::class.java.canonicalName.replace('.', '/')]
    private lateinit var traceCollector: Instruction

    override fun cleanup() {}

    override fun registerPassDependencies() {
        addRequiredProvider<ExecutionContextProvider>()
    }

    private fun prepareStaticInitializer(method: Method) {
        val entryInstructions = buildList<Instruction> {
            traceCollector = getNewCollector()
            +traceCollector
            +disableCollector()
        }
        val exitInstructions = buildList<Instruction> {
            +setNewCollector(traceCollector)
        }
        method.entry.first().insertBefore(entryInstructions)
        val returnInst = method.flatten().filterIsInstance<ReturnInst>().first()
        returnInst.insertBefore(exitInstructions)
    }

    override fun visit(method: Method) {
        if (!method.hasBody) return
        if (method.isStaticInitializer) {
            prepareStaticInitializer(method)
            return
        }
        if (ignores.any { it.isParent(method.klass.pkg) }) return

        val methodEntryInstructions: List<Instruction> = buildList {
            traceCollector = getNewCollector()
            +traceCollector
            val entryMethod = collectorClass.getMethod(
                "methodEnter", types.voidType,
                types.stringType, types.stringType, types.listType,
                types.stringType, types.objectType, types.listType
            )

            val arrayListKlass = cm.arrayListClass
            val initMethod = arrayListKlass.getMethod("<init>", types.voidType)
            val addMethod = arrayListKlass.getMethod("add", types.boolType, types.objectType)

            val argTypesList = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, argTypesList)

            val argumentList = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, argumentList)

            for ((index, arg) in method.argTypes.withIndex()) {
                +arrayListKlass.virtualCall(
                    addMethod, argTypesList, arg.asmDesc.asValue
                )
                val argument = values.getArgument(index, method, arg)
                +arrayListKlass.virtualCall(
                    addMethod, argumentList, argument.wrapped(this)
                )
            }
            val instance = when {
                method.isStatic || method.isConstructor -> values.nullConstant
                else -> values.getThis(method.klass)
            }

            +collectorClass.interfaceCall(
                entryMethod,
                traceCollector,
                method.klass.fullName.asValue,
                method.name.asValue,
                argTypesList,
                method.returnType.asmDesc.asValue,
                instance,
                argumentList
            )
        }
        super.visit(method)
        method.entry.first().insertBefore(methodEntryInstructions)
    }

    override fun visitArrayLoadInst(inst: ArrayLoadInst) {
        val arrayLoadMethod = collectorClass.getMethod(
            "arrayLoad", types.voidType,
            types.stringType, types.stringType, types.stringType,
            types.objectType, types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +addNullityConstraint(inst, inst.arrayRef)
            +addArrayIndexConstraints(inst, inst.arrayRef, inst.index)
            +collectorClass.interfaceCall(
                arrayLoadMethod, traceCollector,
                "$inst".asValue, "${inst.arrayRef}".asValue, "${inst.index}".asValue,
                inst.wrapped(this), inst.arrayRef, inst.index.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitArrayStoreInst(inst: ArrayStoreInst) {
        val arrayStoreMethod = collectorClass.getMethod(
            "arrayStore", types.voidType,
            types.stringType, types.stringType, types.stringType, types.stringType,
            types.objectType, types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +addNullityConstraint(inst, inst.arrayRef)
            +addArrayIndexConstraints(inst, inst.arrayRef, inst.index)
            +collectorClass.interfaceCall(
                arrayStoreMethod, traceCollector,
                "$inst".asValue, "${inst.arrayRef}".asValue, "${inst.index}".asValue, "${inst.value}".asValue,
                inst.arrayRef, inst.index.wrapped(this), inst.value.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitBinaryInst(inst: BinaryInst) {
        val binaryMethod = collectorClass.getMethod(
            "binary", types.voidType,
            types.stringType, types.stringType, types.stringType,
            types.objectType, types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                binaryMethod, traceCollector,
                "$inst".asValue, "${inst.lhv}".asValue, "${inst.rhv}".asValue,
                inst.wrapped(this), inst.lhv.wrapped(this), inst.rhv.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitBranchInst(inst: BranchInst) {
        val branchMethod = collectorClass.getMethod(
            "branch", types.voidType,
            types.stringType, types.stringType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                branchMethod, traceCollector,
                "$inst".asValue, "${inst.cond}".asValue
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitCallInst(inst: CallInst) {
        val callMethod = collectorClass.getMethod(
            "call", types.voidType,
            types.stringType,
            types.stringType, types.stringType, types.listType, types.stringType,
            types.stringType, types.stringType, types.listType,
            types.listType
        )

        val calledMethod = inst.method
        val klass = calledMethod.klass

        val instrumented = buildList<Instruction> {
            if (!inst.isStatic && !inst.method.isConstructor) {
                +addNullityConstraint(inst, inst.callee)
                +addTypeConstraints(inst, inst.callee)
            }

            val arrayListKlass = cm.arrayListClass
            val initMethod = arrayListKlass.getMethod("<init>", types.voidType)
            val addMethod = arrayListKlass.getMethod("add", types.boolType, types.objectType)

            val argTypesList = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, argTypesList)
            for (arg in calledMethod.argTypes) {
                +arrayListKlass.virtualCall(
                    addMethod, argTypesList, arg.asmDesc.asValue
                )
            }

            val argumentList = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, argumentList)
            for (arg in inst.args) {
                +arrayListKlass.virtualCall(
                    addMethod, argumentList, "$arg".asValue
                )
            }

            val concreteArgumentsList = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, concreteArgumentsList)
            for (arg in inst.args) {
                +arrayListKlass.virtualCall(
                    addMethod, concreteArgumentsList, arg.wrapped(this)
                )
            }

            val returnValue = when {
                inst.isNameDefined -> "$inst".asValue
                else -> values.nullConstant
            }
            val callee = when {
                inst.isStatic -> values.nullConstant
                else -> "${inst.callee}".asValue
            }

            +collectorClass.interfaceCall(
                callMethod,
                traceCollector,
                "$inst".asValue,
                klass.fullName.asValue,
                calledMethod.name.asValue,
                argTypesList,
                calledMethod.returnType.asmDesc.asValue,
                returnValue,
                callee,
                argumentList,
                concreteArgumentsList
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitCastInst(inst: CastInst) {
        val castMethod = collectorClass.getMethod(
            "cast", types.voidType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )
        val before = buildList<Instruction> {
            if (inst.type.isReference) +addTypeConstraints(inst, inst.operand, inst.type)
        }
        val after = buildList<Instruction> {
            if (inst.type.isReference) +addNullityConstraint(inst, inst.operand)
            if (inst.type.isReference) +addTypeConstraints(inst, inst.operand, inst.type)

            +collectorClass.interfaceCall(
                castMethod, traceCollector,
                "$inst".asValue, "${inst.operand}".asValue,
                inst.wrapped(this), inst.operand.wrapped(this)
            )
        }
        inst.insertBefore(before)
        inst.insertAfter(after)
    }

    override fun visitCatchInst(inst: CatchInst) {
        val catchMethod = collectorClass.getMethod(
            "catch", types.voidType,
            types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                catchMethod, traceCollector,
                "$inst".asValue, inst
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitCmpInst(inst: CmpInst) {
        val cmpMethod = collectorClass.getMethod(
            "cmp", types.voidType,
            types.stringType, types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                cmpMethod, traceCollector,
                "$inst".asValue, "${inst.lhv}".asValue, "${inst.rhv}".asValue,
                inst.lhv.wrapped(this), inst.rhv.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitEnterMonitorInst(inst: EnterMonitorInst) {
        val enterMonitorMethod = collectorClass.getMethod(
            "enterMonitor", types.voidType,
            types.stringType, types.stringType, types.objectType
        )
        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                enterMonitorMethod, traceCollector,
                "$inst".asValue, "${inst.owner}".asValue, inst.owner
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitExitMonitorInst(inst: ExitMonitorInst) {
        val exitMonitorMethod = collectorClass.getMethod(
            "exitMonitor", types.voidType,
            types.stringType, types.stringType, types.objectType
        )
        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                exitMonitorMethod, traceCollector,
                "$inst".asValue, "${inst.owner}".asValue, inst.owner
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitFieldLoadInst(inst: FieldLoadInst) {
        val fieldLoadMethod = collectorClass.getMethod(
            "fieldLoad", types.voidType,
            types.stringType, types.stringType, types.stringType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            if (!inst.isStatic) +addNullityConstraint(inst, inst.owner)

            val fieldKlass = inst.field.klass.fullName.asValue
            val fieldName = inst.field.name.asValue
            val fieldType = inst.field.type.asmDesc.asValue
            val (owner, concreteOwner) = when {
                inst.isStatic -> values.nullConstant to values.nullConstant
                else -> "${inst.owner}".asValue to inst.owner
            }

            +collectorClass.interfaceCall(
                fieldLoadMethod, traceCollector,
                "$inst".asValue, owner, fieldKlass, fieldName, fieldType,
                inst.wrapped(this), concreteOwner.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitFieldStoreInst(inst: FieldStoreInst) {
        val fieldStoreMethod = collectorClass.getMethod(
            "fieldStore", types.voidType,
            types.stringType,
            types.stringType, types.stringType, types.stringType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            if (!inst.isStatic) +addNullityConstraint(inst, inst.owner)

            val fieldKlass = inst.field.klass.fullName.asValue
            val fieldName = inst.field.name.asValue
            val fieldType = inst.field.type.asmDesc.asValue
            val (owner, concreteOwner) = when {
                inst.isStatic -> values.nullConstant to values.nullConstant
                else -> "${inst.owner}".asValue to inst.owner
            }
            val defOwner = when {
                inst.hasOwner && inst.owner is ThisRef && inst.parent.parent.isConstructor -> values.nullConstant
                else -> concreteOwner
            }

            +collectorClass.interfaceCall(
                fieldStoreMethod, traceCollector,
                "$inst".asValue,
                owner, fieldKlass, fieldName, fieldType, "${inst.value}".asValue,
                inst.value.wrapped(this), defOwner
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitInstanceOfInst(inst: InstanceOfInst) {
        val instanceOfMethod = collectorClass.getMethod(
            "instanceOf", types.voidType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                instanceOfMethod, traceCollector,
                "$inst".asValue, "${inst.operand}".asValue,
                inst.wrapped(this), inst.operand.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitInvokeDynamicInst(inst: InvokeDynamicInst) {
        val invokeDynamicMethod = collectorClass.getMethod(
            "invokeDynamic", types.voidType,
            types.stringType, types.listType,
            types.objectType, types.listType
        )

        val instrumented = buildList<Instruction> {
            val arrayListKlass = cm.arrayListClass
            val initMethod = arrayListKlass.getMethod("<init>", types.voidType)
            val addMethod = arrayListKlass.getMethod("add", types.boolType, types.objectType)
            val args = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, args)
            for (arg in inst.args) {
                +arrayListKlass.virtualCall(
                    addMethod, args, "$arg".asValue
                )
            }

            val concreteArgs = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, concreteArgs)
            for (arg in inst.args) {
                +arrayListKlass.virtualCall(
                    addMethod, concreteArgs, arg.wrapped(this)
                )
            }

            +collectorClass.interfaceCall(
                invokeDynamicMethod, traceCollector,
                "$inst".asValue, args,
                inst.wrapped(this), concreteArgs
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitJumpInst(inst: JumpInst) {
        val jumpMethod = collectorClass.getMethod(
            "jump", types.voidType, types.stringType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                jumpMethod, traceCollector, "$inst".asValue
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitNewArrayInst(inst: NewArrayInst) {
        val newArrayMethod = collectorClass.getMethod(
            "newArray", types.voidType,
            types.stringType, types.listType,
            types.objectType, types.listType
        )

        val instrumented = buildList<Instruction> {
            val arrayListKlass = cm.arrayListClass
            val initMethod = arrayListKlass.getMethod("<init>", types.voidType)
            val addMethod = arrayListKlass.getMethod("add", types.boolType, types.objectType)
            val dimensions = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, dimensions)
            for (dimension in inst.dimensions) {
                +arrayListKlass.virtualCall(
                    addMethod, dimensions, "$dimension".asValue
                )
            }

            val concreteDimensions = types.arrayListType.new().also { +it }
            +arrayListKlass.specialCall(initMethod, concreteDimensions)
            for (dimension in inst.dimensions) {
                +arrayListKlass.virtualCall(
                    addMethod, concreteDimensions, dimension.wrapped(this)
                )
            }

            +collectorClass.interfaceCall(
                newArrayMethod, traceCollector,
                "$inst".asValue, dimensions,
                inst, concreteDimensions
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitNewInst(inst: NewInst) {
        val newMethod = collectorClass.getMethod(
            "new", types.voidType, types.stringType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                newMethod, traceCollector, "$inst".asValue
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitPhiInst(inst: PhiInst) {
        val phiMethod = collectorClass.getMethod(
            "phi", types.voidType, types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                phiMethod, traceCollector, "$inst".asValue, inst.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    override fun visitReturnInst(inst: ReturnInst) {
        val returnMethod = collectorClass.getMethod(
            "ret", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            val (returnValue, concreteValue) = when {
                inst.hasReturnValue -> "${inst.returnValue}".asValue to inst.returnValue
                else -> values.nullConstant to values.nullConstant
            }

            +collectorClass.interfaceCall(
                returnMethod, traceCollector,
                "$inst".asValue, returnValue, concreteValue.wrapped(this)
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitSwitchInst(inst: SwitchInst) {
        val switchMethod = collectorClass.getMethod(
            "switch", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                switchMethod, traceCollector,
                "$inst".asValue, "${inst.key}".asValue, inst.key.wrapped(this)
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitTableSwitchInst(inst: TableSwitchInst) {
        val tableSwitchMethod = collectorClass.getMethod(
            "tableSwitch", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                tableSwitchMethod, traceCollector,
                "$inst".asValue, "${inst.index}".asValue, inst.index.wrapped(this)
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitThrowInst(inst: ThrowInst) {
        val throwMethod = collectorClass.getMethod(
            "throwing", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                throwMethod, traceCollector,
                "$inst".asValue, "${inst.throwable}".asValue, inst.throwable
            )
        }
        inst.insertBefore(instrumented)
    }

    override fun visitUnaryInst(inst: UnaryInst) {
        val unaryMethod = collectorClass.getMethod(
            "unary", types.voidType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        val instrumented = buildList<Instruction> {
            +collectorClass.interfaceCall(
                unaryMethod, traceCollector,
                "$inst".asValue, "${inst.operand}".asValue,
                inst.wrapped(this), inst.operand.wrapped(this)
            )
        }
        inst.insertAfter(instrumented)
    }

    private fun addNullityConstraint(inst: Instruction, value: Value): List<Instruction> = buildList {
        if (inst.parent.parent.isConstructor && value is ThisRef) return@buildList

        val addNullityConstraintsMethod = collectorClass.getMethod(
            "addNullityConstraints", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        +collectorClass.interfaceCall(
            addNullityConstraintsMethod, traceCollector,
            "$inst".asValue, "$value".asValue,
            value.wrapped(this)
        )
    }

    private fun addTypeConstraints(inst: Instruction, value: Value): List<Instruction> = buildList {
        val addTypeConstraintsMethod = collectorClass.getMethod(
            "addTypeConstraints", types.voidType,
            types.stringType, types.stringType, types.objectType
        )

        +collectorClass.interfaceCall(
            addTypeConstraintsMethod, traceCollector,
            "$inst".asValue, "$value".asValue,
            value.wrapped(this)
        )
    }

    private fun addTypeConstraints(inst: Instruction, value: Value, type: Type): List<Instruction> = buildList {
        val addTypeConstraintsMethod = collectorClass.getMethod(
            "addTypeConstraints", types.voidType,
            types.stringType, types.stringType, types.stringType, types.objectType
        )

        +collectorClass.interfaceCall(
            addTypeConstraintsMethod, traceCollector,
            "$inst".asValue, "$value".asValue, type.name.asValue, value.wrapped(this)
        )
    }

    private fun addArrayIndexConstraints(inst: Instruction, array: Value, index: Value): List<Instruction> = buildList {
        val addArrayIndexConstraintsMethod = collectorClass.getMethod(
            "addArrayIndexConstraints", types.voidType,
            types.stringType,
            types.stringType, types.stringType,
            types.objectType, types.objectType
        )

        +collectorClass.interfaceCall(
            addArrayIndexConstraintsMethod, traceCollector,
            "$inst".asValue,
            "$array".asValue, "$index".asValue,
            array.wrapped(this), index.wrapped(this)
        )
    }

    private fun getNewCollector(): Instruction {
        val proxy = cm[TraceCollectorProxy::class.java.canonicalName.replace('.', '/')]
        val getter = proxy.getMethod("currentCollector", MethodDesc(arrayOf(), cm.type.getRefType(collectorClass)))

        return getter.staticCall(proxy, "collector", arrayOf())
    }

    private fun setNewCollector(collector: Value): Instruction {
        val proxy = cm[TraceCollectorProxy::class.java.canonicalName.replace('.', '/')]
        val setter = proxy.getMethod("setCurrentCollector", cm.type.voidType, cm.type.getRefType(collectorClass))

        return setter.staticCall(proxy, arrayOf(collector))
    }

    private fun disableCollector(): Instruction {
        val proxy = cm[TraceCollectorProxy::class.java.canonicalName.replace('.', '/')]
        val disabler = proxy.getMethod("disableCollector", cm.type.voidType)

        return disabler.staticCall(proxy, arrayOf())
    }

    private fun Class.virtualCall(
        method: Method,
        instance: Value,
        vararg args: Value
    ) = method.virtualCall(this, instance, args.toList().toTypedArray())

    private fun Class.interfaceCall(
        method: Method,
        instance: Value,
        vararg args: Value
    ) = method.interfaceCall(this, instance, args.toList().toTypedArray())

    private fun Class.specialCall(
        method: Method,
        instance: Value,
        vararg args: Value
    ) = method.specialCall(this, instance, args.toList().toTypedArray())

    private fun Value.wrapped(list: MutableBuilder<Instruction>): Value = when {
        this.type.isPrimary -> wrapValue(this).also {
            list.inner += it
        }
        else -> this
    }
}