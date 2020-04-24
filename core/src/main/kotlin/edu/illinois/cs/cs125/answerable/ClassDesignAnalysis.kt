package edu.illinois.cs.cs125.answerable

import edu.illinois.cs.cs125.answerable.api.*
import edu.illinois.cs.cs125.answerable.api.defaultToJson
import edu.illinois.cs.cs125.answerable.typeManagement.simpleSourceName
import java.lang.IllegalStateException
import java.lang.reflect.*
import java.util.*

// TODO: analyze inner classes recursively

/**
 * Analyzer that determines that a submission class is equivalent in design to a reference class.
 *
 * The reference class for a question defines a "contract," a specification of the public members that any
 * implementation must expose. Answerable's job is to ascertain that a submission meets the specification both on the
 * surface, in API, and behaviorally. This is the API component.
 */
class ClassDesignAnalysis(private val solutionName: String? = null,
                          private val reference: Class<*>, private val attempt: Class<*>) {
    fun runSuite(
        name: Boolean = true,
        classStatus: Boolean = true,
        classModifiers: Boolean = true,
        typeParams: Boolean = true,
        superClasses: Boolean = true,
        innerClasses: Boolean = true,
        fields: Boolean = true,
        methods: Boolean = true
    ): List<AnalysisOutput> {

        val output = mutableListOf<AnalysisOutput>()
        if (name) output.add(namesMatch())
        if (classStatus) output.add(classStatusMatch())
        if (classModifiers) output.add(classModifiersMatch())
        if (typeParams) output.add(typeParamsMatch())
        if (superClasses) output.add(superClassesMatch())
        /*if (innerClasses) output.add(
            innerClassesMatch( name, classStatus, classModifiers, typeParams,
                superClasses, innerClasses, fields, methods))*/
        if (fields) output.add(publicFieldsMatch())
        if (methods) output.add(publicMethodsMatch())

        return output
    }

    fun namesMatch() = AnalysisOutput(
        AnalysisTag.NAME,
        if (reference.simpleName == attempt.simpleName) {
            Matched(reference.simpleName)
        } else {
            Mismatched(reference.simpleName, attempt.simpleName)
        }
    )

    fun classStatusMatch() = AnalysisOutput(
        AnalysisTag.STATUS,
        if (reference.isInterface == attempt.isInterface) {
            Matched(if (reference.isInterface) "interface" else "class")
        } else {
            val expected = if (reference.isInterface) "interface" else "class"
            val actual = if (attempt.isInterface) "interface" else "class"
            Mismatched(expected, actual)
        }
    )

    fun classModifiersMatch() = AnalysisOutput(
        AnalysisTag.MODIFIERS,
        if (reference.modifiers == attempt.modifiers) {
            Matched(Modifier.toString(reference.modifiers))
        } else {

            val expected = Modifier.toString(reference.modifiers)
            val actual = Modifier.toString(attempt.modifiers)

            Mismatched(expected, actual)
        }
    )

    // Order is significant since it's part of the class API
    fun typeParamsMatch() = AnalysisOutput(AnalysisTag.TYPE_PARAMS, run {
        val refTypes = reference.typeParameters.map { it.name }
        val attTypes = attempt.typeParameters.map { it.name }
        return@run if (refTypes == attTypes) {
            Matched(refTypes)
        } else {
            Mismatched(refTypes, attTypes)
        }
    })

    // Order is not significant here
    fun superClassesMatch() = AnalysisOutput(
        AnalysisTag.SUPERCLASSES,
        if (reference.genericSuperclass == attempt.genericSuperclass
            && reference.genericInterfaces contentEquals attempt.genericInterfaces
        ) {
            Matched(Pair(reference.genericSuperclass, reference.genericInterfaces))
        } else {
            Mismatched(
                Pair(reference.genericSuperclass, reference.genericInterfaces),
                Pair(attempt.genericSuperclass, attempt.genericInterfaces)
            )
        }
    )

    fun innerClassesMatch(name: Boolean = true,
                          classStatus: Boolean = true,
                          classModifiers: Boolean = true,
                          typeParams: Boolean = true,
                          superClasses: Boolean = true,
                          innerClasses: Boolean = true,
                          fields: Boolean = true,
                          methods: Boolean = true): AnalysisOutput {
        val innerClassResults: List<List<AnalysisOutput>> = reference.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .map { rclz -> attempt.declaredClasses
                .find { it.simpleName == rclz.simpleName }
                .let { Pair(rclz, it) }
            }
            // TODO: get a mismatched in there if second doesn't exist...
            .map { ClassDesignAnalysis(solutionName, it.first, it.second!!).runSuite(
                name, classStatus, classModifiers, typeParams, superClasses, innerClasses, fields, methods
            )}

        // how to structure inner class results?
        // I see two options:
        // (1) record the enclosing class name of all entities (using them as "<enclosing class>.<entity name>")
        //     and then flatten the analysis output lists together such that the results of ALL method analysis,
        //     both enclosing and inner, are in the same AnalysisOutput, for example.
        // (2) Have this method return a special subclass of AnalysisOutput which contains a
        //     Map<String, List<AnalysisOutput>>. This would be a list of analysis results for each inner class,
        //     by name.
        //     The Matched/Mismatched expected/found fields would be for the lists of public inner class names.
        //     To get an error message out of this in an organized manner is significantly easier, simply
        //     In inner class <name>:
        //         <error message>
        // So I think I favor option 2.

        return AnalysisOutput(AnalysisTag.INNERCLASSES, Matched(Unit))
    }

    private val referenceAnnotations = setOf(
        Generator::class.java,
        Verify::class.java,
        Next::class.java,
        EdgeCase::class.java,
        SimpleCase::class.java,
        Precondition::class.java,
        Helper::class.java,
        Ignore::class.java
    )

    fun publicFieldsMatch() = AnalysisOutput(AnalysisTag.FIELDS, run {
        fun mkFieldString(field: Field): String {
            val sb = StringBuilder()
            printModifiersIfNonzero(sb, field.modifiers, false)
            sb.append(field.type.simpleName).append(" ")
            sb.append(field.simpleName())
            return sb.toString()
        }

        // order is irrelevant; we only care about the API
        val refFields =
            reference.getPublicFields()
                .filter { field -> referenceAnnotations.none { field.isAnnotationPresent(it) } }
                .map(::mkFieldString)
                .sorted()
        val attFields =
            attempt.getPublicFields()
                .map(::mkFieldString)
                .sorted()

        return@run if (refFields == attFields) {
            Matched(refFields)
        } else {
            Mismatched(refFields, attFields)
        }
    })

    fun publicMethodsMatch() = AnalysisOutput(AnalysisTag.METHODS, run {
        val refMethodData =
            (reference.getPublicMethods() + reference.constructors) // TODO: double check that this doesn't add private constrs?
                .filter { method -> referenceAnnotations.none { method.isAnnotationPresent(it) } }
                .filterOut { method ->
                    method.getAnnotation(Solution::class.java)?.name?.let { it != solutionName } ?: false
                }
                .map { MethodData(it) }
                .sortedBy { it.signature }
        val attMethodData =
            (attempt.getPublicMethods() + attempt.constructors)
                .map { MethodData(it) }
                .sortedBy { it.signature }

        // if everything matched, then we're done.
        return@run if (refMethodData == attMethodData) {
            Matched(refMethodData)
        } else {
            Mismatched(refMethodData, attMethodData)
        }
    })
}

enum class AnalysisTag {
    NAME, STATUS, MODIFIERS, TYPE_PARAMS, SUPERCLASSES, INNERCLASSES, FIELDS, METHODS;

    override fun toString(): String = name.toLowerCase().replace('_', ' ')
}

// alias filterNot with a more sensible name
fun <T> Iterable<T>.filterOut(p: (T) -> Boolean) = this.filterNot(p)

class AnalysisOutput(val tag: AnalysisTag, val result: AnalysisResult<*>) : DefaultSerializable {
    override fun toString() = this.toErrorMsg() // see ClassDesignErrors.kt
    override fun toJson() = this.defaultToJson()
}

fun List<AnalysisOutput>.toJson() = this.joinToString(prefix = "[", postfix = "]") { it.toJson() }

sealed class AnalysisResult<out T> : DefaultSerializable {
    override fun toJson() = this.defaultToJson()
}
    data class Matched<T>(val found: T) : AnalysisResult<T>()
    data class Mismatched<T>(val expected: T, val found: T) : AnalysisResult<T>()

class MethodData(
    val method: Executable
) {
    val name: String = method.name.split(".").last()
    val typeParams: Array<out TypeVariable<*>> = method.typeParameters
    val returnType: Type = when (method) {
        is Method -> method.genericReturnType
        is Constructor<*> -> method.declaringClass
        else -> throw IllegalStateException("This can't happen (non method/constructor in MethodData).")
    }
    val paramTypes: Array<out Type> = method.genericParameterTypes
    val exceptionTypes: Array<out Type> = method.genericExceptionTypes
    val isDefault: Boolean = when (method) {
        is Method -> method.isDefault
        is Constructor<*> -> false
        else -> false
    }

    val signature: String = toGenericString(method.modifiers, isDefault)

    // The below two methods are adapted out of openJDK's Executable.java file to Kotlin.
    // Note the change in creation of the name. We explicitly do *not* fully qualify it.
    private fun toGenericString(modifierMask: Int, isDefault: Boolean): String {
        try {
            val sb = StringBuilder()

            printModifiersIfNonzero(sb, modifierMask, isDefault)

            val typeparams = typeParams
            if (typeparams.isNotEmpty()) {
                sb.append(
                    typeparams.joinToString(
                        prefix = "<",
                        separator = ", ",
                        postfix = "> ",
                        transform = { it.simpleSourceName })
                )
            }

            if (method is Method) {
                sb.append(returnType.simpleSourceName).append(" ")
            }
            sb.append(name)

            sb.append('(')
            val sj = StringJoiner(", ")
            val params = paramTypes
            for (j in params.indices) {
                var param = params[j].simpleSourceName
                if (method.isVarArgs && j == params.size - 1)
                // replace T[] with T...
                    param = param.replaceFirst("\\[]$".toRegex(), "...")
                sj.add(param)
            }
            sb.append(sj.toString())
            sb.append(')')

            val exceptionTypes = exceptionTypes
            if (exceptionTypes.isNotEmpty()) {
                sb.append(
                    exceptionTypes.joinToString(
                        separator = ", ",
                        prefix = " throws ",
                        transform = { it.simpleSourceName })
                )
            }
            return sb.toString()
        } catch (e: Exception) {
            return "<$e>"
        }
    }

    override fun toString(): String = signature

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodData) return false

        if (signature != other.signature) return false

        return true
    }

    override fun hashCode(): Int {
        return signature.hashCode()
    }
}

private fun printModifiersIfNonzero(sb: StringBuilder, mask: Int, isDefault: Boolean) {
    var mod = mask

    if (mod != 0 && !isDefault) {
        sb.append(Modifier.toString(mod)).append(' ')
    } else {
        val accessModifiers = Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE
        val accessMod = mod and (accessModifiers) // infix bitwise &
        if (accessMod != 0)
            sb.append(Modifier.toString(accessMod)).append(' ')
        if (isDefault)
            sb.append("default ")
        mod = mod and accessModifiers.inv()
        if (mod != 0)
            sb.append(Modifier.toString(mod)).append(' ')
    }
}

// Unqualified names
fun Field.simpleName(): String = this.name.split(".").last()
fun Type.simpleName(): String = this.typeName.split(".").last()