@file:Suppress("TooManyFunctions", "SpreadOperator")

package edu.illinois.cs.cs125.answerable.classmanipulation

import edu.illinois.cs.cs125.answerable.AnswerableVerificationException
import edu.illinois.cs.cs125.answerable.annotations.EdgeCase
import edu.illinois.cs.cs125.answerable.annotations.Generator
import edu.illinois.cs.cs125.answerable.annotations.Helper
import edu.illinois.cs.cs125.answerable.annotations.Next
import edu.illinois.cs.cs125.answerable.annotations.SimpleCase
import edu.illinois.cs.cs125.answerable.annotations.Verify
import edu.illinois.cs.cs125.answerable.classdesignanalysis.answerableName
import edu.illinois.cs.cs125.answerable.classdesignanalysis.simpleName
import edu.illinois.cs.cs125.answerable.dotName
import edu.illinois.cs.cs125.answerable.slashName
import org.apache.bcel.Const
import org.apache.bcel.classfile.ConstantCP
import org.apache.bcel.classfile.ConstantClass
import org.apache.bcel.classfile.ConstantFieldref
import org.apache.bcel.classfile.ConstantInterfaceMethodref
import org.apache.bcel.classfile.ConstantMethodref
import org.apache.bcel.classfile.ConstantNameAndType
import org.apache.bcel.classfile.ConstantUtf8
import org.apache.bcel.classfile.InnerClass
import org.apache.bcel.classfile.InnerClasses
import org.apache.bcel.classfile.Method
import org.apache.bcel.classfile.NestHost
import org.apache.bcel.classfile.NestMembers
import org.apache.bcel.classfile.SourceFile
import org.apache.bcel.classfile.StackMap
import org.apache.bcel.generic.ArrayType
import org.apache.bcel.generic.CPInstruction
import org.apache.bcel.generic.ClassGen
import org.apache.bcel.generic.FieldGen
import org.apache.bcel.generic.FieldInstruction
import org.apache.bcel.generic.FieldOrMethod
import org.apache.bcel.generic.INVOKEDYNAMIC
import org.apache.bcel.generic.InstructionList
import org.apache.bcel.generic.InvokeInstruction
import org.apache.bcel.generic.MethodGen
import org.apache.bcel.generic.ObjectType
import org.apache.bcel.generic.Type
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.UUID

// [Note: "scalar"]
// The "scalar" of a type is the underlying type of a nested array type; or just the type if it is not an array type.
// Ex: the scalar of int[] is int, the scalar of Object[][] is Object, the scalar of String is String.
/**
 * Mirrors one class, which may be an inner class. (Recursive helper for mkGeneratorMirrorClass.)
 * @param baseClass the class to mirror
 * @param referenceClass the original, outermost reference class
 * @param targetClass the original, outermost submission class (to generate instances of)
 * @param mirrorName the desired fully-qualified dot name of the mirror class
 * @param mirrorsMade the map of names to finished mirrors
 * @param pool the type pool to get bytecode from
 * @return the mirrored class
 */
@Suppress("ComplexMethod", "NestedBlockDepth", "ReturnCount", "LongParameterList")
private fun mkGeneratorMirrorClass(
    baseClass: Class<*>,
    referenceClass: Class<*>,
    targetClass: Class<*>,
    mirrorName: String,
    mirrorsMade: MutableMap<String, Class<*>>,
    pool: TypePool
): Class<*> {
    mirrorsMade[mirrorName]?.let { return it }

    val refLName = "L${referenceClass.slashName()};"
    val subLName = "L${targetClass.slashName()};"
    val mirrorSlashName = mirrorName.slashName()
    val refLBase = "L${referenceClass.slashName()}$"
    val mirrorLBase = "L${mirrorSlashName.split("$", limit = 2)[0]}$"

    val classGen = ClassGen(pool.getBcelClassForClass(baseClass))
    val constantPoolGen = classGen.constantPool
    val constantPool = constantPoolGen.constantPool

    val newClassIdx = constantPoolGen.addClass(targetClass.canonicalName)
    val mirrorClassIdx = constantPoolGen.addClass(mirrorSlashName)
    val refMirrorClassIdx = constantPoolGen.addClass(mirrorSlashName.split("$", limit = 2)[0])
    classGen.classNameIndex = mirrorClassIdx

    @Suppress("CascadeIf")
    fun fixType(type: Type): Type {
        // trim [ from the type signature because we care about what is in arrays, not that it is an array
        val newName = if (type.signature.trimStart('[') == refLName) {
            // If the (scalar of the) type is the reference, use the target's qualified name
            targetClass.canonicalName
        } else if (type.signature.trimStart('[').startsWith(refLBase)) {
            // above but inner class
            type.signature.trimStart('[').trimEnd(';').replace(refLBase, mirrorLBase).trimStart('L')
        } else {
            // Nothing to do.
            return type
        }
        return if (type is ArrayType) {
            ArrayType(newName, type.dimensions)
        } else {
            ObjectType(newName)
        }
    }

    /**
     * Take the current working index inside the constant pool. If the type needs to be fixed (c.f. fixType)
     * Then a replacement class constant is inserted into the constant pool replacement index is returned.
     * Else, returns null.
     */
    fun classIndexReplacement(currentIndex: Int): Int? {
        val classConst = constantPool.getConstant(currentIndex) as? ConstantClass ?: return null
        val className = (constantPool.getConstant(classConst.nameIndex) as? ConstantUtf8)?.bytes ?: return null
        val curType = if (className.startsWith("[")) Type.getType(className) else ObjectType(className)
        val newType = fixType(curType)
        return when {
            newType.signature == curType.signature -> {
                currentIndex
            }
            newType is ArrayType -> {
                constantPoolGen.addArrayClass(newType)
            }
            else -> {
                constantPoolGen.addClass(newType as ObjectType)
            }
        }
    }

    /**
     * Take the name of an inner class of the reference class, and return the name
     * of the corresponding inner class of the submission class. (Protected by CDA)
     */
    fun fixOuterClassName(innerName: String): String {
        val topLevelMirrorName = mirrorName.split("$", limit = 2)[0]
        val innerPath = innerName.split("$", limit = 2)[1]
        return "$topLevelMirrorName\$$innerPath"
    }

    fun methodHasAnnotation(memberName: String, annotations: Set<Class<out Annotation>>): Boolean =
        referenceClass.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == memberName
        }?.let { method ->
            annotations.any { annotation -> method.isAnnotationPresent(annotation) }
        } ?: false

    fun methodHasGenerationAnnotation(memberName: String) = methodHasAnnotation(memberName, generationAnnotationTypes)
    fun methodHasVerifyAnnotation(memberName: String) = methodHasAnnotation(memberName, verifyAnnotationType)

    fun fieldHasGenerationAnnotation(memberName: String): Boolean =
        referenceClass.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == memberName
        }?.let { field ->
            generationAnnotationTypes.any { annotation -> field.isAnnotationPresent(annotation) }
        } ?: false

    /**
     * Handles the case for mirroring where a constant pool constant is a ConstantCP,
     * specifically, a reference to a field, method, or interface method.
     */
    fun handleConstantCP(constant: ConstantCP) {
        if (!(
            constant is ConstantFieldref ||
                constant is ConstantMethodref ||
                constant is ConstantInterfaceMethodref
            )
        ) return
        if (constant.classIndex == 0 || constantPool.getConstant(constant.classIndex) !is ConstantClass) return

        val className = constant.getClass(constantPool)

        if (className == referenceClass.canonicalName) {
            // if the constant is the name of the reference class, it is something that might need to be mirrored
            val memberName = (
                constantPool.getConstant(constant.nameAndTypeIndex)
                    as ConstantNameAndType
                ).getName(constantPool)
            val shouldReplace: Boolean =
                if (constant is ConstantMethodref || constant is ConstantInterfaceMethodref) {
                    // if it contains a '$' it's a synthetic accessor, which needs to be mirrored
                    // they could be helper functions involved in needed inner classes, but the user can't @Helper them
                    methodHasGenerationAnnotation(memberName) || memberName.contains('$')
                } else if (constant is ConstantFieldref) {
                    fieldHasGenerationAnnotation(memberName)
                } else error("The impossible happened! handleConstantCP: bad constant type.\nPlease report a bug.")
            constant.classIndex = if (shouldReplace) refMirrorClassIdx else newClassIdx
        } else if (className.startsWith("${referenceClass.canonicalName}$")) {
            // inner class that needs to be duplicated.
            constant.classIndex = constantPoolGen.addClass(fixOuterClassName(className).slashName())
        }
    }

    /**
     * Handle the signature of something, which may need to have references to the reference class mirrored.
     */
    fun handleConstantNameAndType(constant: ConstantNameAndType) {
        val typeSignature = constant.getSignature(constantPool)
        if (typeSignature.contains(refLName) || typeSignature.contains(refLBase)) {
            val fixedSignature = typeSignature.replace(refLName, subLName).replace(refLBase, mirrorLBase)
            // clobber the constant holding the old signature, we don't need it
            constantPoolGen.setConstant(constant.signatureIndex, ConstantUtf8(fixedSignature))
        }
    }

    /**
     * Handle a class constant.
     */
    fun handleConstantClass(constant: ConstantClass) {
        val name = constant.getBytes(constantPool)
        if (name.startsWith("${baseClass.slashName()}\$")) {
            // if the referred class is an inner class of the current class, recurse and make mirrors in it as well.
            val inner = pool.classForName(name.dotName())
            val innerMirror = mkGeneratorMirrorClass(
                inner, referenceClass, targetClass, fixOuterClassName(name), mirrorsMade, pool
            )
            constantPoolGen.setConstant(constant.nameIndex, ConstantUtf8(innerMirror.slashName()))
        } else if (name.startsWith("${referenceClass.slashName()}\$")) {
            // inner class of the outermost class, which is not inside of the current inner class.
            // Shouldn't merge this with the above condition
            // because of possible mutual reference (infinite recursion)
            constantPoolGen.setConstant(constant.nameIndex, ConstantUtf8(fixOuterClassName(name).slashName()))
        }
    }

    /**
     * Handle a method on the mirror class. Either:
     *   (1) it is not needed for generation purposes, and it is dropped; or
     *   (2) it is needed, and all references to the reference class are mirrored.
     */
    fun handleMethod(method: Method) {
        classGen.removeMethod(method)
        if ((!method.isStatic || methodHasVerifyAnnotation(method.name)) && baseClass == referenceClass) return

        val newMethod = MethodGen(method, classGen.className, constantPoolGen)
        newMethod.argumentTypes = method.argumentTypes.map(::fixType).toTypedArray()
        newMethod.returnType = fixType(method.returnType)
        newMethod.instructionList.map { handle -> handle.instruction }.filterIsInstance<CPInstruction>()
            .forEach { instr ->
                classIndexReplacement(instr.index)?.let { newIdx -> instr.index = newIdx }
            }

        newMethod.codeAttributes.filterIsInstance<StackMap>().firstOrNull()?.let { stackMap ->
            stackMap.stackMap.forEach { stackEntry ->
                stackEntry.typesOfLocals.plus(stackEntry.typesOfStackItems)
                    .filter { local -> local.type == Const.ITEM_Object }.forEach { local ->
                        classIndexReplacement(local.index)?.let { newIdx -> local.index = newIdx }
                    }
            }
        }
        newMethod.localVariables.forEach { localVariableGen ->
            localVariableGen.type = fixType(localVariableGen.type)
        }

        classGen.addMethod(newMethod.method)
    }

    fun handleField(field: org.apache.bcel.classfile.Field) {
        classGen.removeField(field)

        val newField = FieldGen(field, constantPoolGen)
        newField.type = fixType(field.type)

        classGen.addField(newField.field)
    }

    fun handleInnerClass(innerClass: InnerClass) {
        val outerName =
            (constantPool.getConstant(innerClass.outerClassIndex) as? ConstantClass)?.getBytes(constantPool)
        if (outerName == baseClass.slashName()) {
            innerClass.outerClassIndex = mirrorClassIdx
        }
    }

    // THE ACTUAL FUNCTION BODY
    // finally

    // Mirror all references to the reference class in the constant pool.
    (1 until constantPoolGen.size).map(constantPoolGen::getConstant).forEach { constant ->
        when (constant) {
            is ConstantCP -> handleConstantCP(constant)
            is ConstantClass -> handleConstantClass(constant)
            is ConstantNameAndType -> handleConstantNameAndType(constant)
        }
    }

    // Mirror all references to the reference class in the class methods.
    classGen.methods.forEach(::handleMethod)
    // Mirror all references to the reference class in the class fields.
    classGen.fields.forEach(::handleField)
    // Mirror all references to the reference class in the inner class table.
    // there is at most one InnerClasses attribute on any classGen.
    classGen.attributes.filterIsInstance<InnerClasses>().firstOrNull()?.innerClasses?.forEach(::handleInnerClass)
    // Mirror Java 11 nesting attributes
    classGen.attributes.filterIsInstance<NestHost>().firstOrNull()?.let { nestHost ->
        nestHost.hostClassIndex = refMirrorClassIdx
    }
    classGen.attributes.filterIsInstance<NestMembers>().firstOrNull()?.let { nestMembers ->
        nestMembers.classes =
            nestMembers.classNames.map { constantPoolGen.addClass(fixOuterClassName(it)) }.toIntArray()
    }

    // classGen.javaClass.dump("Fiddled${mirrorsMade.size}.class") // Uncomment for debugging
    return pool.loadMirrorBytes(mirrorName, classGen.javaClass, baseClass).also { mirrorsMade[mirrorName] = it }
}

private val generationAnnotationTypes: Set<Class<out Annotation>> =
    setOf(
        Helper::class.java,
        Generator::class.java,
        Next::class.java,
        EdgeCase::class.java,
        SimpleCase::class.java
    )

private val verifyAnnotationType: Set<Class<out Annotation>> = setOf(Verify::class.java)

/**
 * Creates a mirror class containing copies of generators from the [originalClass], retargeted
 * so that references to the [originalClass] have been replaced with references to the [targetClass].
 *
 * @param originalClass the original reference class
 * @param targetClass the class the generators should refer to instead of [originalClass]
 * @param pool the type pool to get bytecode from
 * @return a mirror class suitable only for generation
 */
internal fun mkGeneratorMirrorClass(
    originalClass: Class<*>,
    targetClass: Class<*>,
    pool: TypePool = TypePool(),
    namePrefix: String = "m"
): Class<*> {
    return mkGeneratorMirrorClass(
        originalClass, originalClass, targetClass,
        "answerablemirror.$namePrefix" + UUID.randomUUID().toString().replace("-", ""), mutableMapOf(), pool
    )
}

/**
 * Creates a mirror of an outer class with `final` members removed from classes and methods.
 * This allows making proxies (which have to inherit and override) for final classes or classes with final members.
 * @param clazz an outer class
 * @param pool the type pool to get bytecode from and load classes into
 * @return a non-final version of the class with non-final members/classes
 */
internal fun mkOpenMirrorClass(clazz: Class<*>, pool: TypePool, namePrefix: String = "o"): Class<*> {
    return mkOpenMirrorClass(clazz, mapOf(), pool, namePrefix)
}

/**
 * Creates a renamed open mirror, with the specified class references remapped.
 * @param clazz an outer class
 * @param classRenames replacements to make (current class to replacement class)
 * @param pool the type pool to get bytecode from and load classes into
 * @return a non-final version of the class with non-final members/classes,
 *         and references to all renamed classes updated
 */
internal fun mkOpenMirrorClass(
    clazz: Class<*>,
    classRenames: Map<Class<*>, Class<*>>,
    pool: TypePool,
    namePrefix: String = "o"
): Class<*> {
    val newName = "answerablemirror.$namePrefix" + UUID.randomUUID().toString().replace("-", "")
    val allRenames = classRenames
        .map { (inClass, outClass) -> Pair(inClass.name, outClass.name) }
        .plus(Pair(clazz.name, newName))
        .map { Pair(it.first.replace('.', '/'), it.second.replace('.', '/')) }
    return mkOpenMirrorClass(clazz, clazz, newName, allRenames.toMap(), mutableListOf(), pool)!!
}

/**
 * Worker function for opening a class and remapping references.
 * @param clazz one class to transform
 * @param baseClass the outer class of the class to transform (may be the same as clazz)
 * @param newName the new name for the transformed equivalent of clazz
 * @param classRenames all class renames to perform
 * @param alreadyDone list of names of classes already processed (to avoid infinite recursion)
 * @param pool the type pool to get bytecode from and load classes into
 * @return the transformed version of clazz, or null if it was already handled by a different call
 */
@Suppress("ComplexMethod", "LongParameterList", "NestedBlockDepth")
private fun mkOpenMirrorClass(
    clazz: Class<*>,
    baseClass: Class<*>,
    newName: String,
    classRenames: Map<String, String>,
    alreadyDone: MutableList<String>,
    pool: TypePool
): Class<*>? {
    if (alreadyDone.contains(newName)) return null
    alreadyDone.add(newName)

    // Get a mutable ClassGen, initialized as a copy of the existing class
    val classGen = ClassGen(pool.getBcelClassForClass(clazz))
    val constantPoolGen = classGen.constantPool
    val constantPool = constantPoolGen.constantPool

    // Strip `final` off the class and its methods
    if (Modifier.isFinal(classGen.modifiers)) classGen.modifiers -= Modifier.FINAL
    classGen.methods.forEach { method ->
        if (Modifier.isFinal(method.modifiers)) method.modifiers -= Modifier.FINAL
    }

    // Recursively mirror inner classes
    val newBase = newName.split('$', limit = 2)[0]
    classGen.attributes.filterIsInstance<InnerClasses>().firstOrNull()?.innerClasses?.forEach { innerClass ->
        val innerName =
            (constantPool.getConstant(innerClass.innerClassIndex) as? ConstantClass)?.getBytes(constantPool)
                ?: return@forEach
        if (innerName.startsWith(baseClass.slashName() + "$")) {
            if (Modifier.isFinal(innerClass.innerAccessFlags)) innerClass.innerAccessFlags -= Modifier.FINAL
            val innerPath = innerName.split('$', limit = 2)[1]
            mkOpenMirrorClass(
                pool.classForName(innerName.replace('/', '.')), baseClass,
                "$newBase\$$innerPath", classRenames, alreadyDone, pool
            )
        }
    }

    /**
     * Transforms a signature to refer to the new names.
     * @param signature a signature from a NameAndType constant or a compound type name (so L names are involved)
     * @return the signature with class mentions remapped according to classRenames
     */
    fun fixSignature(signature: String): String {
        var editedSignature = signature
        classRenames.forEach { (orig, new) ->
            editedSignature = editedSignature.replace("L$orig;", "L$new;").replace("L$orig$", "L$new$")
        }
        return editedSignature
    }

    // Rename the class by changing all strings used by class or signature constants
    (1 until constantPool.length).forEach { idx ->
        val constant = constantPool.getConstant(idx)
        if (constant is ConstantClass) {
            val className = constant.getBytes(constantPool)
            val classNameParts = className.split('$', limit = 2)
            val newConstantValue = if (classNameParts[0] in classRenames.keys) {
                // Reference to a plain class (not L-named)
                val newSlashBase = classRenames[classNameParts[0]]
                if (classNameParts.size > 1) "$newSlashBase\$${classNameParts[1]}" else newSlashBase
            } else if (className.contains(';')) {
                // Compound class name (like an array or generic type, includes an L name)
                fixSignature(className)
            } else {
                // Primitive array type or something similarly irrelevant
                className
            }
            constantPoolGen.setConstant(constant.nameIndex, ConstantUtf8(newConstantValue))
        } else if (constant is ConstantNameAndType) {
            // An actual signature, which will always L-name classes
            val signature = constant.getSignature(constantPool)
            constantPoolGen.setConstant(constant.signatureIndex, ConstantUtf8(fixSignature(signature)))
        }
    }

    // Rewrite signatures of all methods and fields
    classGen.methods.map { it.signatureIndex }.union(classGen.fields.map { it.signatureIndex }).forEach { sigIdx ->
        val signature = (constantPool.getConstant(sigIdx) as ConstantUtf8).bytes
        constantPoolGen.setConstant(sigIdx, ConstantUtf8(fixSignature(signature)))
    }

    // Create and load the modified class
    // classGen.javaClass.dump("Opened${alreadyDone.indexOf(newName)}.class") // Uncomment for debugging
    return pool.loadMirrorBytes(newName, classGen.javaClass, clazz)
}

@Suppress("MagicNumber")
private fun modifierIsSynthetic(flags: Int) = (flags and 0x00001000) != 0

/**
 * Throws AnswerableBytecodeVerificationException if a mirror of the given generator class would fail with an
 * illegal or absent member access.
 * @param referenceClass the original, non-mirrored reference class
 * @param pool the type pool to get bytecode from
 */
internal fun verifyMemberAccess(referenceClass: Class<*>, pool: TypePool = TypePool()) {
    verifyMemberAccess(referenceClass, referenceClass, mutableSetOf(), mapOf(), pool)
}

/**
 * Verifies the given class, which may be an inner class. (Recursive helper for the above overload.)
 * @param currentClass the class to verify
 * @param referenceClass the original, outermost reference class
 * @param checked the collection of classes already verified
 * @param dangerousAccessors members whose access will cause the given problem
 * @param pool the type pool to get bytecode from
 */
@Suppress("ComplexMethod", "ThrowsCount", "ReturnCount")
private fun verifyMemberAccess(
    currentClass: Class<*>,
    referenceClass: Class<*>,
    checked: MutableSet<Class<*>>,
    dangerousAccessors: Map<String, AnswerableBytecodeVerificationException>,
    pool: TypePool
) {
    if (checked.contains(currentClass)) return
    checked.add(currentClass)

    val toCheck = pool.getBcelClassForClass(currentClass)
    val methodsToCheck = if (currentClass == referenceClass) {
        // On the outer class, we only need to check functions involved in generation
        toCheck.methods.filter {
            it.annotationEntries.any { ae ->
                ae.annotationType in generationAnnotationTypes.map { t -> ObjectType(t.name).signature }
            }
        }.toTypedArray()
    } else {
        // Anything in an inner class could be involved in generation
        toCheck.methods
    }

    val constantPool = toCheck.constantPool
    val innerClassIndexes =
        toCheck.attributes.filterIsInstance<InnerClasses>().firstOrNull()?.innerClasses?.filter { innerClass ->
            (constantPool.getConstant(innerClass.innerClassIndex) as ConstantClass).getBytes(constantPool)
                .startsWith("${toCheck.className.slashName()}$")
        }?.map { it.innerClassIndex } ?: listOf()

    // Mutable copy of all the dangers to inner classes so we can add items
    val dangersToInnerClasses = dangerousAccessors.toMutableMap()

    val methodsChecked = mutableSetOf<Method>()
    fun checkMethod(method: Method, checkInner: Boolean) {
        methodsChecked.add(method)

        fun checkFieldAccessInstruction(signatureConstant: ConstantNameAndType) {
            // Assume the field is actually present, if not, the user is asking for trouble
            // The compiler is always right here
            val field = referenceClass.getDeclaredField(signatureConstant.getName(constantPool))
            // Static helper fields are safe
            if (Modifier.isStatic(field.modifiers) && field.isAnnotationPresent(Helper::class.java)) return
            // Nonpublic methods are specific to the implementation and should not be used from generators
            if (!Modifier.isPublic(field.modifiers))
                throw AnswerableBytecodeVerificationException(method.name, currentClass, field)
        }

        fun checkMethodCallInstruction(signatureConstant: ConstantNameAndType) {
            val name = signatureConstant.getName(constantPool)
            val signature = signatureConstant.getSignature(constantPool)
            if (name == "<init>") {
                // Make sure the specific constructor referenced is public
                referenceClass.declaredConstructors.firstOrNull { dc ->
                    !Modifier.isPublic(dc.modifiers) &&
                        signature == "(${dc.parameterTypes.joinToString(separator = "") {
                        Type.getType(it).signature
                    }})V"
                }?.let { candidate ->
                    throw AnswerableBytecodeVerificationException(method.name, currentClass, candidate)
                }
            } else {
                // Make sure the specific method overload referenced is usable
                referenceClass.declaredMethods.firstOrNull { dm ->
                    // Find the overload mentioned...
                    dm.name == name &&
                        !Modifier.isPublic(dm.modifiers) &&
                        Type.getSignature(dm) == signature &&
                        (
                            generationAnnotationTypes.none { dm.isAnnotationPresent(it) } ||
                                !Modifier.isStatic(dm.modifiers)
                            ) // ...and see if it's unsafe
                }?.let { candidate ->
                    // The candidate is extremely sketchy
                    dangerousAccessors[candidate.name]?.let {
                        throw AnswerableBytecodeVerificationException(it, method.name, currentClass)
                    }
                    // Static synthetic functions can be implicitly @Helper, otherwise it's unsafe
                    if (!modifierIsSynthetic(candidate.modifiers))
                        throw AnswerableBytecodeVerificationException(method.name, currentClass, candidate)
                }
            }
        }

        // Look through the code of the method to make sure it doesn't refer to unsafe members
        // Instructions that don't reference the constant pool are always safe (never refer to members)
        InstructionList(method.code.code).map { it.instruction }.filterIsInstance<CPInstruction>()
            .forEach eachInstr@{ instr ->
                if (instr is FieldOrMethod) {
                    if (instr is INVOKEDYNAMIC) return@eachInstr // Invoke-dynamic IDs aren't the same kind of thing
                    val refConstant = constantPool.getConstant(instr.index) as? ConstantCP ?: return@eachInstr
                    if (refConstant.getClass(constantPool) != referenceClass.name) return@eachInstr
                    // Now that we know it's mentioning the reference class,
                    // we need to make sure the member access is valid
                    val signatureConst = constantPool.getConstant(refConstant.nameAndTypeIndex) as ConstantNameAndType
                    if (instr is FieldInstruction) {
                        checkFieldAccessInstruction(signatureConst)
                    } else if (instr is InvokeInstruction) {
                        checkMethodCallInstruction(signatureConst)
                    }
                } else if (checkInner) {
                    // At the first instantiation of an inner class in a generation-related method,
                    // we have to consider that inner class involved in generation,
                    // so it needs to be verified.
                    val classConstant = constantPool.getConstant(instr.index) as? ConstantClass ?: return@eachInstr
                    if (innerClassIndexes.contains(instr.index)) {
                        // Recursively check the inner class
                        verifyMemberAccess(
                            pool.classForName(classConstant.getBytes(constantPool).dotName()),
                            referenceClass, checked, dangersToInnerClasses, pool
                        )
                    }
                }
            }
    }

    if (referenceClass == currentClass) {
        toCheck.methods.filter { modifierIsSynthetic(it.modifiers) }.forEach {
            // Check synthetic functions
            try {
                checkMethod(it, false)
            } catch (e: AnswerableBytecodeVerificationException) {
                // Remember the problem this function will cause if used from a generation function
                // Don't throw it immediately because it might harmlessly be used elsewhere
                dangersToInnerClasses[it.name] = e
            }
        }
    }

    // Check generation functions, exploding if there's a hazard
    methodsToCheck.forEach { checkMethod(it, true) }
}

/**
 * Finds the Kotlin file class for the file in which a class was defined.
 * @param forClass a non-file class whose file class to find
 * @param typePool the type pool to load bytecode from
 * @return the Kotlin file class, or null if no such class
 */
@Suppress("ReturnCount")
internal fun getDefiningKotlinFileClass(forClass: Class<*>, typePool: TypePool): Class<*>? {
    val bcelClass = typePool.getBcelClassForClass(forClass)
    val sourceFile = bcelClass.attributes.filterIsInstance<SourceFile>().firstOrNull() ?: return null
    val filename = sourceFile.sourceFileName ?: return null
    return try {
        val packagePrefix = if (forClass.packageName.isEmpty()) "" else forClass.packageName + "."
        forClass.classLoader.loadClass(packagePrefix + filename.replace(".kt", "Kt"))
    } catch (e: ClassNotFoundException) {
        null
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class AnswerableBytecodeVerificationException internal constructor(
    val blameMethod: String,
    val blameClass: Class<*>,
    val member: Member
) : AnswerableVerificationException("Bytecode error not specified. Please report a bug.") {

    override val message: String
        get() {
            return "\nMirrorable method `$blameMethod' in ${describeClass(blameClass)} " +
                when (member) {
                    is java.lang.reflect.Method ->
                        "calls non-public submission method: ${member.answerableName()}"
                    is Field ->
                        "uses non-public submission field: ${member.name}"
                    is Constructor<*> ->
                        "uses non-public submission constructor: ${member.answerableName()}"
                    else -> throw IllegalStateException(
                        "Invalid type of AnswerableBytecodeVerificationException.member. Please report a bug."
                    )
                }
        }

    private fun describeClass(clazz: Class<*>): String {
        return "`${clazz.simpleName()}'" + (
            clazz.enclosingMethod?.let {
                " (inside `${it.name}' method of ${describeClass(clazz.enclosingClass)})"
            } ?: ""
            )
    }

    internal constructor(
        fromInner: AnswerableBytecodeVerificationException,
        blameMethod: String,
        blameClass: Class<*>
    ) : this(blameMethod, blameClass, fromInner.member) {
        initCause(fromInner)
    }
}
