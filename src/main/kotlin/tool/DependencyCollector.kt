package tool

import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

class DependencyCollector(private val cp: ClasspathIndex) {
    // Reachability sets
    val keepClasses = linkedSetOf<String>()
    val keepFields = mutableSetOf<Triple<String,String,String>>() // owner, name, desc
    val keepMethods = mutableSetOf<Triple<String,String,String>>()

    private val workClasses = ArrayDeque<String>()
    private val workFields = ArrayDeque<Triple<String,String,String>>()
    private val workMethods = ArrayDeque<Triple<String,String,String>>()

    fun seed(roots: List<RootSig>) {
        for (r in roots) when (r) {
            is ClassSig -> addClass(r.owner)
            is FieldSig -> {
                val desc = if (r.desc.isNotEmpty()) r.desc else resolveFieldDesc(r.owner, r.name)
                if (desc != null) addField(r.owner, r.name, desc) else addClass(r.owner)
            }
            is MethodSig -> addMethod(r.owner, r.name, r.desc)
        }
        run()
    }

    private fun resolveFieldDesc(owner: String, name: String): String? {
        val cn = cp.readClassNode(owner) ?: return null
        for (f in cn.fields) if (f.name == name) return f.desc
        return null
    }

    private fun run() {
        while (true) {
            when {
                workClasses.isNotEmpty() -> processClass(workClasses.removeFirst())
                workFields.isNotEmpty() -> processField(workFields.removeFirst())
                workMethods.isNotEmpty() -> processMethod(workMethods.removeFirst())
                else -> return
            }
        }
    }

    private fun addClass(c: String) {
        if (keepClasses.add(c)) workClasses.add(c)
    }

    private fun addField(owner: String, name: String, desc: String) {
        val key = Triple(owner, name, desc)
        if (keepFields.add(key)) workFields.add(key)
        addClass(owner)
    }

    private fun addMethod(owner: String, name: String, desc: String) {
        val key = Triple(owner, name, desc)
        if (keepMethods.add(key)) workMethods.add(key)
        addClass(owner)
    }

    private fun processClass(name: String) {
        val cn = cp.readClassNode(name) ?: return
        // class header
        cn.superName?.let { addClass(it) }
        cn.interfaces?.forEach { addClass(it) }
        collectTypesFromSignature(cn.signature, keepClasses)

        // nest / enclosing / inner
        cn.outerClass?.let { addClass(it) }
        if (cn.outerClass != null && cn.outerMethod != null && cn.outerMethodDesc != null) {
            addMethod(cn.outerClass, cn.outerMethod, cn.outerMethodDesc)
        }
        cn.nestHostClass?.let { addClass(it) }
        cn.nestMembers?.forEach { addClass(it) }
        cn.permittedSubclasses?.forEach { addClass(it) }
        cn.recordComponents?.forEach { rc ->
            DescDeps.typesFromFieldDesc(rc.descriptor).forEach(::addClass)
            collectTypesFromSignature(rc.signature, keepClasses)
            collectTypesFromAnnotations(rc.visibleAnnotations as? List<org.objectweb.asm.tree.AnnotationNode>, keepClasses)
            collectTypesFromAnnotations(rc.invisibleAnnotations as? List<org.objectweb.asm.tree.AnnotationNode>, keepClasses)
        }

        collectTypesFromAnnotations(cn.visibleAnnotations as? List<AnnotationNode>, keepClasses)
        collectTypesFromAnnotations(cn.invisibleAnnotations as? List<AnnotationNode>, keepClasses)

        // Fields
        for (f in cn.fields) {
            DescDeps.typesFromFieldDesc(f.desc).forEach(::addClass)
            collectTypesFromSignature(f.signature, keepClasses)
            collectTypesFromAnnotations(f.visibleAnnotations as? List<AnnotationNode>, keepClasses)
            collectTypesFromAnnotations(f.invisibleAnnotations as? List<AnnotationNode>, keepClasses)
        }

        // Methods
        for (m in cn.methods) {
            DescDeps.typesFromMethodDesc(m.desc).forEach(::addClass)
            m.exceptions?.forEach { addClass(it) }
            collectTypesFromSignature(m.signature, keepClasses)
            collectTypesFromAnnotations(m.visibleAnnotations as? List<AnnotationNode>, keepClasses)
            collectTypesFromAnnotations(m.invisibleAnnotations as? List<AnnotationNode>, keepClasses)
            m.visibleParameterAnnotations?.forEach { ann -> collectTypesFromAnnotations(ann?.toList(), keepClasses) }
            m.invisibleParameterAnnotations?.forEach { ann -> collectTypesFromAnnotations(ann?.toList(), keepClasses) }
            m.visibleTypeAnnotations?.forEach { ta ->
                try { Type.getType(ta.desc).internalName.let(keepClasses::add) } catch (_: Throwable) {}
            }
            m.invisibleTypeAnnotations?.forEach { ta ->
                try { Type.getType(ta.desc).internalName.let(keepClasses::add) } catch (_: Throwable) {}
            }
        }

        // InnerClasses attribute lists relationships; add owners and inners
        cn.innerClasses?.forEach { ic ->
            ic.name?.let { addClass(it) }
            ic.outerName?.let { addClass(it) }
        }
    }

    private fun processField(triple: Triple<String,String,String>) {
        val (owner, name, desc) = triple
        val cn = cp.readClassNode(owner) ?: return
        DescDeps.typesFromFieldDesc(desc).forEach(::addClass)
        cn.fields.find { it.name == name && (triple.third.isEmpty() || it.desc == triple.third) }?.let { f ->
            collectTypesFromSignature(f.signature, keepClasses)
            collectTypesFromAnnotations(f.visibleAnnotations as? List<AnnotationNode>, keepClasses)
            collectTypesFromAnnotations(f.invisibleAnnotations as? List<AnnotationNode>, keepClasses)
        }
    }

    private fun processMethod(triple: Triple<String,String,String>) {
        val (owner, name, desc) = triple
        val cn = cp.readClassNode(owner) ?: return
        DescDeps.typesFromMethodDesc(desc).forEach(::addClass)
        cn.methods.find { it.name == name && it.desc == desc }?.let { m ->
            m.exceptions?.forEach { addClass(it) }
            collectTypesFromSignature(m.signature, keepClasses)
            collectTypesFromAnnotations(m.visibleAnnotations as? List<AnnotationNode>, keepClasses)
            collectTypesFromAnnotations(m.invisibleAnnotations as? List<AnnotationNode>, keepClasses)
            m.visibleParameterAnnotations?.forEach { ann -> collectTypesFromAnnotations(ann?.toList(), keepClasses) }
            m.invisibleParameterAnnotations?.forEach { ann -> collectTypesFromAnnotations(ann?.toList(), keepClasses) }
        }
    }
}