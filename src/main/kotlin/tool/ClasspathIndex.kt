package tool

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Indexes the classpath JARs with first-jar-wins semantics. */
class ClasspathIndex(private val jars: List<Path>) {
    data class Entry(val jar: Path, val pathInJar: String)

    private val classes: MutableMap<String, Entry> = LinkedHashMap()

    init {
        for (jar in jars) {
            if (!Files.exists(jar)) continue
            JarFile(jar.toFile()).use { jf ->
                val e = jf.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    if (!entry.name.endsWith(".class")) continue
                    val internalName = entry.name.removeSuffix(".class")
                    // First JAR wins
                    classes.putIfAbsent(internalName, Entry(jar, entry.name))
                }
            }
        }
    }

    fun readClassNode(internalName: String): ClassNode? {
        val hit = classes[internalName] ?: return null
        JarFile(hit.jar.toFile()).use { jf ->
            val je = jf.getJarEntry(hit.pathInJar) ?: return null
            jf.getInputStream(je).use { ins ->
                val cr = ClassReader(ins)
                val node = ClassNode()
                cr.accept(node, ClassReader.SKIP_DEBUG)
                return node
            }
        }
    }

    fun hasClass(internalName: String): Boolean = classes.containsKey(internalName)

    fun allKnownClasses(): Set<String> = classes.keys
}