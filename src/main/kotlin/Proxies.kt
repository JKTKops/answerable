package edu.illinois.cs.cs125.answerable

import javassist.util.proxy.Proxy
import javassist.util.proxy.ProxyFactory
import org.apache.bcel.Repository
import org.apache.bcel.classfile.ConstantCP
import org.apache.bcel.classfile.ConstantClass
import org.apache.bcel.classfile.ConstantUtf8
import org.apache.bcel.classfile.StackMap
import org.apache.bcel.generic.*
import org.objenesis.ObjenesisStd
import org.objenesis.instantiator.ObjectInstantiator

val objenesis = ObjenesisStd()

/*
 * For performance reasons, we want to re-use instantiators as much as possible.
 * A map is used for future-safety so that as many proxy instantiators as are needed can be created safely,
 * even if using only one is the most common use case.
 *
 * We map from 'superClass' instead of directly from 'proxyClass' as we won't have access to
 * the same reference to 'proxyClass' on future calls.
 */
val proxyInstantiators: MutableMap<Class<*>, ObjectInstantiator<out Any?>> = mutableMapOf()

fun mkProxy(superClass: Class<*>, childClass: Class<*>, forward: Any?): Any {
    // if we don't have an instantiator for this proxy class, make a new one
    val instantiator = proxyInstantiators[superClass] ?: run {
        val factory = ProxyFactory()

        factory.superclass = superClass
        factory.setFilter { it.name != "finalize" }
        val proxyClass = factory.createClass()

        val newInstantiator = objenesis.getInstantiatorOf(proxyClass)
        proxyInstantiators[superClass] = newInstantiator
        newInstantiator
    }
    val subProxy = instantiator.newInstance()

    (subProxy as Proxy).setHandler { _, method, _, args ->
        childClass.getMethod(method.name, *method.parameterTypes).invoke(forward, *args)
    }

    return subProxy
}

val num = 0

internal fun mkGeneratorMirrorClass(referenceClass: Class<*>, targetClass: Class<*>): Class<*> {
    fun fixType(type: Type): Type {
        if (type.signature == "L${referenceClass.canonicalName.replace('.','/')};") {
            return ObjectType(targetClass.canonicalName)
        }
        return type
    }
    val generatorName = referenceClass.declaredMethods.firstOrNull { it.isAnnotationPresent(Generator::class.java) && it.returnType == referenceClass }?.name
    val atNextName = referenceClass.declaredMethods.firstOrNull { it.isAnnotationPresent(Next::class.java) && it.returnType == referenceClass }?.name
    val atVerifyName = referenceClass.declaredMethods.firstOrNull { it.isAnnotationPresent(Verify::class.java) }?.name

    val classGen = ClassGen(Repository.lookupClass(referenceClass))

    val constantPoolGen = classGen.constantPool
    val constantPool = constantPoolGen.constantPool
    val newClassIdx = constantPoolGen.addClass(targetClass.canonicalName)
    for (i in 1 until constantPoolGen.size) {
        val constant = constantPoolGen.getConstant(i)
        if (constant is ConstantCP) {
            if (constant.classIndex == 0) continue
            val className = constant.getClass(constantPool)
            if (className == referenceClass.canonicalName) {
                constant.classIndex = newClassIdx
            }
        }
    }
    classGen.methods.forEach {
        classGen.removeMethod(it)
        if (!it.isStatic || it.name == atVerifyName) return@forEach

        val newName = when (it.name) {
            generatorName -> "answerable\$generate"
            atNextName -> "answerable\$next"
            else -> it.name
        }
        val newReturnType = fixType(it.returnType)
        val instructions = InstructionList(it.code.code)
        instructions.forEach { instructionHandle ->
            val instr = instructionHandle.instruction
            if (instr is NEW) {
                val classConst = constantPool.getConstant(instr.index) as ConstantClass
                if ((constantPool.getConstant(classConst.nameIndex) as ConstantUtf8?)?.bytes == referenceClass.canonicalName.replace('.', '/')) {
                    instr.index = newClassIdx
                }
            }
        }


        val argumentTypes = it.argumentTypes.map(::fixType).toTypedArray()

        classGen.addMethod(MethodGen(it.accessFlags, newReturnType, argumentTypes, null, newName, null, instructions, classGen.constantPool)
                .also { mg ->
                    mg.maxLocals = it.code.maxLocals
                    mg.maxStack = it.code.maxStack
                    it.code.attributes.filter { attribute -> attribute is StackMap }.map { sm -> mg.addCodeAttribute(sm) }
                }.method)
    }

    val loader = object : ClassLoader() {
        fun loadBytes(name: String, bytes: ByteArray): Class<*> {
            val clazz = defineClass(name, bytes, 0, bytes.size)
            resolveClass(clazz)
            return clazz
        }
    }

    return loader.loadBytes(referenceClass.canonicalName, classGen.javaClass.bytes)
}