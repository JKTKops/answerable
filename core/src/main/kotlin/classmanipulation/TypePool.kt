@file:Suppress("SpreadOperator")

package edu.illinois.cs.cs125.answerable.classmanipulation

import edu.illinois.cs.cs125.answerable.api.BytecodeProvider
import edu.illinois.cs.cs125.answerable.api.EnumerableBytecodeLoader
import javassist.util.proxy.ProxyFactory
import org.apache.bcel.Repository
import org.apache.bcel.classfile.ClassParser
import org.apache.bcel.classfile.JavaClass
import org.objenesis.ObjenesisStd
import org.objenesis.instantiator.ObjectInstantiator
import java.lang.reflect.Type
import java.util.WeakHashMap

private val objenesis = ObjenesisStd()

/**
 * Manages a collection of types: their object instantiators, their bytecode, and the classes themselves.
 * One type pool represents one source of classes, e.g. there would be one type pool for the reference and
 * another for a submission.
 *
 * Like classloaders, type pools can have a parent which they will ask for types they don't themselves have.
 */
internal class TypePool(private val bytecodeProvider: BytecodeProvider? = null, val parent: TypePool? = null) {

    /*
     * For performance reasons, we want to re-use instantiators as much as possible.
     * A map is used for future-safety so that as many proxy instantiators as are needed can be created safely,
     * even if using only one is the most common use case.
     *
     * We map from 'superClass' instead of directly from 'proxyClass' as we won't have access to
     * the same reference to 'proxyClass' on future calls.
     */
    private val proxyInstantiators: MutableMap<Class<*>, ObjectInstantiator<*>> = mutableMapOf()

    class ProxyHolder(val proxy: Any) {
        // Prevent calls to proxied equals and hashCode
        override fun equals(other: Any?): Boolean {
            if (other !is ProxyHolder) return false
            return other.proxy === proxy
        }

        override fun hashCode(): Int = System.identityHashCode(proxy)
    }

    private val proxyOriginals: WeakHashMap<ProxyHolder, Any> = WeakHashMap()

    private var loader: BytesClassLoader =
        BytesClassLoader()
    private val bytecode = mutableMapOf<Class<*>, ByteArray>()

    /**
     * Tracks the original class from which each class was mirrored.
     * Used to get nicer error messages from TestGeneration.
     */
    private val mirrorOriginalTypes = mutableMapOf<Class<*>, Type>()

    constructor(bytecodeProvider: BytecodeProvider?, commonLoader: ClassLoader) : this(bytecodeProvider) {
        loader = BytesClassLoader(commonLoader)
    }

    constructor(parent: TypePool, vararg otherParents: TypePool) : this(null, parent) {
        loader = DiamondClassLoader(
            parent.loader,
            *otherParents.map { it.loader }.toTypedArray()
        )
    }

    fun getBcelClassForClass(clazz: Class<*>): JavaClass {
        @Suppress("TooGenericExceptionCaught")
        return try {
            parent?.getBcelClassForClass(clazz)!!
        } catch (e: Exception) {
            (
                bytecode[clazz] ?: bytecodeProvider?.getBytecode(clazz) ?: Repository.lookupClass(clazz)
                    .also { Repository.clearCache() }.bytes
                )?.let {
                ClassParser(it.inputStream(), clazz.name).parse()
            } ?: throw NoClassDefFoundError("Could not find bytecode for $clazz")
        }
    }

    fun loadMirrorBytes(name: String, bcelClass: JavaClass, mirroredFrom: Type): Class<*> {
        val bytes = bcelClass.bytes
        return loader.loadBytes(name, bytes).also {
            bytecode[it] = bytes
            mirrorOriginalTypes[it] = getOriginalClass(mirroredFrom)
        }
    }

    fun classForName(name: String): Class<*> {
        // TODO: Unsure whether it's useful to initialize the class immediately
        return Class.forName(name, false, loader)
    }

    fun getProxyInstantiator(superClass: Class<*>): ObjectInstantiator<*> {
        return proxyInstantiators.getOrPut(superClass) {
            val factory = ProxyFactory()
            factory.superclass = superClass
            factory.setFilter { it.name != "finalize" }
            val proxyClass = factory.createClass()
            objenesis.getInstantiatorOf(proxyClass)
        }
    }

    fun recordProxyOriginal(behavior: Any, presentation: Any) {
        proxyOriginals[ProxyHolder(presentation)] = behavior
    }

    fun getProxyOriginal(presentation: Any): Any? {
        return proxyOriginals[ProxyHolder(presentation)]
    }

    fun getLoader(): EnumerableBytecodeLoader {
        return loader
    }

    fun getOriginalClass(type: Type): Type {
        if (type !is Class<*>) return type
        return mirrorOriginalTypes[type] ?: parent?.getOriginalClass(type) ?: type
    }

    fun takeOriginalClassMappings(otherPool: TypePool, transformedLoader: ClassLoader) {
        otherPool.mirrorOriginalTypes.forEach { (transformed, original) ->
            mirrorOriginalTypes[transformedLoader.loadClass(transformed.name)] = original
        }
    }
}

/**
 * Lots of modifications done in this file affect the bytecode of a class, so we need a way to hot-reload those classes
 * and get them back into the JVM. Here we can take a bytearray (class file) and load it.
 */
internal open class BytesClassLoader(parentLoader: ClassLoader? = null) :
    ClassLoader(parentLoader ?: getSystemClassLoader()),
    EnumerableBytecodeLoader {
    private val bytecodeLoaded = mutableMapOf<Class<*>, ByteArray>()
    private val definedClasses = mutableMapOf<String, Class<*>>()
    fun loadBytes(name: String, bytes: ByteArray): Class<*> {
        return definedClasses.getOrPut(name) {
            defineClass(name, bytes, 0, bytes.size).also { bytecodeLoaded[it] = bytes }
        }
    }

    override fun getBytecode(clazz: Class<*>): ByteArray {
        return bytecodeLoaded[clazz]
            ?: throw ClassNotFoundException("This BytesClassLoader is not responsible for $clazz")
    }

    override fun getAllBytecode(): Map<String, ByteArray> {
        return bytecodeLoaded.map { (key, value) -> key.name to value }.toMap()
    }

    override fun getLoader(): ClassLoader {
        return this
    }
}

/**
 * Like [BytesClassLoader], but also can ask other classloaders for classes. Useful in sandboxes.
 */
internal class DiamondClassLoader(
    primaryParent: ClassLoader,
    private vararg val otherParents: ClassLoader
) : BytesClassLoader(primaryParent) {
    override fun loadClass(name: String?): Class<*> {
        try {
            return super.loadClass(name)
        } catch (e: ClassNotFoundException) {
            otherParents.forEach {
                try {
                    return it.loadClass(name)
                } catch (ignored: ClassNotFoundException) {
                }
            }
        }
        throw ClassNotFoundException(name)
    }
}
