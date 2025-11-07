package tool

import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.AnnotationNode

/** Collects internal names from a JVM signature string (generic signature). */
fun collectTypesFromSignature(sig: String?, into: MutableSet<String>) {
    if (sig == null) return
    try {
        SignatureReader(sig).accept(object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) { into.add(name) }
        })
    } catch (_: Throwable) {
        // Be forgiving about malformed signatures
    }
}

/** Adds internal types referenced by annotations. */
fun collectTypesFromAnnotations(anns: List<AnnotationNode>?, into: MutableSet<String>) {
    anns?.forEach { an ->
        try {
            Type.getType(an.desc).internalName.let(into::add)
        } catch (_: Throwable) { }
        // Annotation element values may include Type instances
        fun scan(v: Any?) {
            when (v) {
                is Type -> into.add(v.internalName)
                is List<*> -> v.forEach(::scan)
                is Array<*> -> v.forEach(::scan)
            }
        }
        an.values?.chunked(2)?.forEach { pair ->
            if (pair.size == 2) scan(pair[1])
        }
    }
}

/** Ensures a trivial method body. Constructors get a `super()` call. */
fun emitTrivialMethodBody(mw: MethodVisitor, access: Int, name: String, desc: String, ownerSuperName: String?) {
    val isAbstract = (access and Opcodes.ACC_ABSTRACT) != 0
    val isNative = (access and Opcodes.ACC_NATIVE) != 0
    if (isAbstract || isNative) return // leave as is

    mw.visitCode()
    if (name == "<init>") {
        // load this, call super.<init>()V, return
        mw.visitVarInsn(Opcodes.ALOAD, 0)
        val superName = ownerSuperName ?: "java/lang/Object"
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        mw.visitInsn(Opcodes.RETURN)
        mw.visitMaxs(0, 0)
        mw.visitEnd()
        return
    }

    val ret = Type.getReturnType(desc)
    when (ret.sort) {
        Type.VOID -> mw.visitInsn(Opcodes.RETURN)
        Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
            mw.visitInsn(Opcodes.ICONST_0); mw.visitInsn(Opcodes.IRETURN)
        }
        Type.LONG -> { mw.visitInsn(Opcodes.LCONST_0); mw.visitInsn(Opcodes.LRETURN) }
        Type.FLOAT -> { mw.visitInsn(Opcodes.FCONST_0); mw.visitInsn(Opcodes.FRETURN) }
        Type.DOUBLE -> { mw.visitInsn(Opcodes.DCONST_0); mw.visitInsn(Opcodes.DRETURN) }
        else -> { mw.visitInsn(Opcodes.ACONST_NULL); mw.visitInsn(Opcodes.ARETURN) }
    }
    mw.visitMaxs(0, 0)
    mw.visitEnd()
}