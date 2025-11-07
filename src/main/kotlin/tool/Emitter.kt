package tool

import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class Emitter(private val cp: ClasspathIndex) {
    fun emit(classes: Set<String>, keepFields: Set<Triple<String,String,String>>, keepMethods: Set<Triple<String,String,String>>, outJar: Path) {
        outJar.parent?.let { Files.createDirectories(it) }
        JarOutputStream(Files.newOutputStream(outJar)).use { jos ->
            for (name in classes) {
                val cn = cp.readClassNode(name) ?: continue
                val bytes = transform(cn, keepFields, keepMethods)
                val je = JarEntry("$name.class")
                jos.putNextEntry(je)
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    private fun transform(cn: ClassNode, keepFields: Set<Triple<String,String,String>>, keepMethods: Set<Triple<String,String,String>>): ByteArray {
        // Filter members by reachability (owner == cn.name)
        val keptFields = cn.fields.filter { f -> keepFields.any { it.first == cn.name && it.second == f.name && (it.third.isEmpty() || it.third == f.desc) } }
        val keptMethodsList = cn.methods.filter { m -> keepMethods.any { it.first == cn.name && it.second == m.name && it.third == m.desc } }

        val isInterface = (cn.access and Opcodes.ACC_INTERFACE) != 0
        val isAbstract = (cn.access and Opcodes.ACC_ABSTRACT) != 0
        val hadCtor = cn.methods.any { it.name == "<init>" }
        val hasCtorKept = keptMethodsList.any { it.name == "<init>" }

        val out = ClassNode()
        out.version = cn.version
        out.access = cn.access
        out.name = cn.name
        out.signature = cn.signature
        out.superName = cn.superName
        out.interfaces = ArrayList(cn.interfaces)
        out.sourceFile = cn.sourceFile
        out.sourceDebug = cn.sourceDebug
        out.outerClass = cn.outerClass
        out.outerMethod = cn.outerMethod
        out.outerMethodDesc = cn.outerMethodDesc
        out.visibleAnnotations = cn.visibleAnnotations?.toMutableList()
        out.invisibleAnnotations = cn.invisibleAnnotations?.toMutableList()
        out.attrs = cn.attrs
        out.nestHostClass = cn.nestHostClass
        out.nestMembers = cn.nestMembers
        out.permittedSubclasses = cn.permittedSubclasses
        out.recordComponents = cn.recordComponents
        out.innerClasses = cn.innerClasses

        // Fields
        for (f in keptFields) {
            val nf = FieldNode(f.access, f.name, f.desc, f.signature, /*value*/ null)
            nf.visibleAnnotations = f.visibleAnnotations?.toMutableList()
            nf.invisibleAnnotations = f.invisibleAnnotations?.toMutableList()
            out.fields.add(nf)
        }

        // Methods
        for (m in keptMethodsList) {
            val nm = MethodNode(m.access and (Opcodes.ACC_ABSTRACT.inv()) and (Opcodes.ACC_NATIVE.inv()), m.name, m.desc, m.signature, m.exceptions?.toTypedArray())
            nm.visibleAnnotations = m.visibleAnnotations?.toMutableList()
            nm.invisibleAnnotations = m.invisibleAnnotations?.toMutableList()
            nm.visibleParameterAnnotations = m.visibleParameterAnnotations
            nm.invisibleParameterAnnotations = m.invisibleParameterAnnotations
            nm.annotationDefault = m.annotationDefault
            out.methods.add(nm)
        }

        // Add a synthetic trivial no-arg ctor if needed
        if (!isInterface && !isAbstract && hadCtor && !hasCtorKept) {
            val acc = Opcodes.ACC_PUBLIC
            val nm = MethodNode(acc, "<init>", "()V", null, null)
            out.methods.add(nm)
        }

        // Write class and fill method bodies
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        out.accept(object : ClassVisitor(Opcodes.ASM9, cw) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitEnd() {
                        emitTrivialMethodBody(mv, access, name, descriptor, out.superName)
                        super.visitEnd()
                    }
                }
            }
        })

        // Prune Kotlin metadata if present
        try {
            val cn2 = ClassNode()
            ClassReader(cw.toByteArray()).accept(cn2, 0)
            val changed = MetadataPruner.pruneIfPresent(cn2, keepMethods, keepFields)
            if (changed) {
                val cw2 = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
                cn2.accept(cw2)
                return cw2.toByteArray()
            }
        } catch (_: Throwable) { }

        return cw.toByteArray()
    }
}