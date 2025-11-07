package tool

import kotlinx.metadata.KmClass
import kotlinx.metadata.KmPackage
import kotlinx.metadata.jvm.*
import kotlin.Metadata as KotlinMetadataAnn
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

/**
 * Best-effort Kotlin metadata pruning to reflect removed members.
 * If metadata can't be parsed or mapped, leave it unchanged.
 */
object MetadataPruner {
    private const val KOTLIN_METADATA_DESC = "Lkotlin/Metadata;"

    fun pruneIfPresent(
        cn: ClassNode,
        keepMethods: Set<Triple<String, String, String>>,
        keepFields: Set<Triple<String, String, String>>
    ): Boolean {
        val anns = (cn.visibleAnnotations ?: emptyList()) + (cn.invisibleAnnotations ?: emptyList())
        val mAnn = anns.find { it.desc == KOTLIN_METADATA_DESC } as? AnnotationNode ?: return false

        // Convert ASM AnnotationNode -> real kotlin.Metadata annotation
        val metadataAnn = toMetadata(mAnn) ?: return false

        // Read structured metadata
        val kcm = KotlinClassMetadata.readStrict(metadataAnn) ?: return false

        return when (kcm) {
            is KotlinClassMetadata.Class -> {
                val km = kcm.kmClass
                val changed = pruneKmClass(cn.name, km, keepMethods, keepFields)
                if (changed) replaceAnnotation(cn, kcm) else false
            }
            is KotlinClassMetadata.FileFacade -> {
                val km = kcm.kmPackage
                val changed = pruneKmPackage(cn.name, km, keepMethods, keepFields)
                if (changed) replaceAnnotation(cn, kcm) else false
            }
            is KotlinClassMetadata.MultiFileClassPart -> {
                val km = kcm.kmPackage
                val changed = pruneKmPackage(cn.name, km, keepMethods, keepFields)
                if (changed) replaceAnnotation(cn, kcm) else false
            }
            is KotlinClassMetadata.SyntheticClass,
            is KotlinClassMetadata.MultiFileClassFacade,
            is KotlinClassMetadata.Unknown -> false
        }
    }

    // ------------------------ pruning ------------------------

    private fun pruneKmClass(
        owner: String,
        km: KmClass,
        keepMethods: Set<Triple<String, String, String>>,
        keepFields: Set<Triple<String, String, String>>
    ): Boolean {
        var changed = false

        // NOTE: jvmSignature -> signature (kotlin.metadata.jvm.signature extension)
        km.constructors.removeIf { ctor ->
            val sig = ctor.signature?.let { Triple(owner, it.name, it.descriptor) }
            val drop = sig != null && sig !in keepMethods
            if (drop) changed = true
            drop
        }

        km.functions.removeIf { fn ->
            val sig = fn.signature?.let { Triple(owner, it.name, it.descriptor) }
            val drop = sig != null && sig !in keepMethods
            if (drop) changed = true
            drop
        }

        km.properties.removeIf { p ->
            val getterSig = p.getterSignature?.let { Triple(owner, it.name, it.descriptor) }
            val setterSig = p.setterSignature?.let { Triple(owner, it.name, it.descriptor) }
            val fieldSig  = p.fieldSignature?.let  { Triple(owner, it.name, it.descriptor) }

            val keep =
                listOfNotNull(getterSig, setterSig).any { it in keepMethods } ||
                        (fieldSig != null && fieldSig in keepFields)

            val drop = !keep
            if (drop) changed = true
            drop
        }

        return changed
    }

    private fun pruneKmPackage(
        owner: String,
        km: KmPackage,
        keepMethods: Set<Triple<String, String, String>>,
        keepFields: Set<Triple<String, String, String>>
    ): Boolean {
        var changed = false

        km.functions.removeIf { fn ->
            val sig = fn.signature?.let { Triple(owner, it.name, it.descriptor) }
            val drop = sig != null && sig !in keepMethods
            if (drop) changed = true
            drop
        }

        km.properties.removeIf { p ->
            val getterSig = p.getterSignature?.let { Triple(owner, it.name, it.descriptor) }
            val setterSig = p.setterSignature?.let { Triple(owner, it.name, it.descriptor) }
            val fieldSig  = p.fieldSignature?.let  { Triple(owner, it.name, it.descriptor) }

            val keep =
                listOfNotNull(getterSig, setterSig).any { it in keepMethods } ||
                        (fieldSig != null && fieldSig in keepFields)

            val drop = !keep
            if (drop) changed = true
            drop
        }

        return changed
    }

    // ------------------------ writing back ------------------------

    private fun replaceAnnotation(cn: ClassNode, meta: KotlinClassMetadata.Class): Boolean {
        val newMetadata = meta.write()          // returns kotlin.Metadata
        writeBack(cn, newMetadata)
        return true
    }

    private fun replaceAnnotation(cn: ClassNode, meta: KotlinClassMetadata.FileFacade): Boolean {
        val newMetadata = meta.write()
        writeBack(cn, newMetadata)
        return true
    }

    private fun replaceAnnotation(cn: ClassNode, meta: KotlinClassMetadata.MultiFileClassPart): Boolean {
        val newMetadata = meta.write()
        writeBack(cn, newMetadata)
        return true
    }

    private fun writeBack(cn: ClassNode, metadata: KotlinMetadataAnn) {
        replaceMetadataAnnotation(cn, metadata)
    }

    private fun replaceMetadataAnnotation(cn: ClassNode, m: KotlinMetadataAnn) {
        fun toAnnNode(mm: KotlinMetadataAnn): AnnotationNode {
            val an = AnnotationNode(KOTLIN_METADATA_DESC)
            val values = arrayListOf<Any>(
                "k",  mm.kind,
                "mv", mm.metadataVersion.toList(),
                "bv", mm.bytecodeVersion.toList(),
                "d1", mm.data1.toList(),
                "d2", mm.data2.toList(),
            )
            mm.extraString.let { values.addAll(listOf("xs", it)) }
            mm.packageName.let { values.addAll(listOf("pn", it)) }
            if (mm.extraInt  != 0) values.addAll(listOf("xi", mm.extraInt))
            an.values = values
            return an
        }

        fun mutate(list: MutableList<AnnotationNode>?) {
            if (list == null) return
            val idx = list.indexOfFirst { it.desc == KOTLIN_METADATA_DESC }
            val replacement = toAnnNode(m)
            if (idx >= 0) {
                list[idx] = replacement
            } else {
                list.add(replacement)
            }
        }

        mutate(cn.visibleAnnotations)
        mutate(cn.invisibleAnnotations)
    }

    // ------------------------ AnnotationNode -> @Metadata ------------------------

    private fun toMetadata(an: AnnotationNode): KotlinMetadataAnn? {
        val raw = an.values ?: return null
        val map = mutableMapOf<String, Any?>()
        for (i in raw.indices step 2) {
            val key = raw[i] as String
            val value = raw[i + 1]
            map[key] = value
        }

        val kind = map["k"] as? Int ?: return null
        val mv   = (map["mv"] as? List<Int>)?.toIntArray() ?: intArrayOf()
        val bv   = (map["bv"] as? List<Int>)?.toIntArray() ?: intArrayOf()
        val d1   = (map["d1"] as? List<String>)?.toTypedArray() ?: emptyArray()
        val d2   = (map["d2"] as? List<String>)?.toTypedArray() ?: emptyArray()
        val xs   = map["xs"] as? String ?: ""
        val pn   = map["pn"] as? String ?: ""
        val xi   = (map["xi"] as? Int) ?: 0

        // Recommended way: instantiate @Metadata directly from Kotlin
        return KotlinMetadataAnn(
            kind,
            mv,
            bv,
            d1,
            d2,
            xs,
            pn,
            xi
        )
    }
}
