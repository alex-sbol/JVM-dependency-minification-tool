package tool

import org.objectweb.asm.Type

sealed interface RootSig {
    val owner: String // internal name (e.g., com/google/gson/Gson)
}

data class ClassSig(override val owner: String): RootSig

data class FieldSig(override val owner: String, val name: String, val desc: String): RootSig

data class MethodSig(override val owner: String, val name: String, val desc: String): RootSig

object SigParser {
    // Accepts lines like:
    //  org/jetbrains/annotations/ApiStatus$Internal
    //  com/google/gson/JsonNull#INSTANCE
    //  com/google/gson/Gson#newBuilder()Lcom/google/gson/GsonBuilder;
    fun parse(line: String): RootSig? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val hash = trimmed.indexOf('#')
        return if (hash < 0) {
            ClassSig(trimmed)
        } else {
            val owner = trimmed.substring(0, hash)
            val rest = trimmed.substring(hash + 1)
            val paren = rest.indexOf('(')
            if (paren < 0) {
                // field
                FieldSig(owner, rest, "") // desc unknown yet; will resolve from class
            } else {
                // method name + desc
                val name = rest.substring(0, paren)
                val desc = rest.substring(paren)
                MethodSig(owner, name, desc)
            }
        }
    }
}

// Helpers to scan descriptors for class types
object DescDeps {
    fun typesFromMethodDesc(desc: String): Set<String> {
        val out = mutableSetOf<String>()
        Type.getMethodType(desc).argumentTypes.forEach { t -> addType(t, out) }
        addType(Type.getReturnType(desc), out)
        return out
    }

    fun typesFromFieldDesc(desc: String): Set<String> {
        val out = mutableSetOf<String>()
        addType(Type.getType(desc), out)
        return out
    }

    private fun addType(t: Type, out: MutableSet<String>) {
        when (t.sort) {
            Type.ARRAY -> addType(t.elementType, out)
            Type.OBJECT -> out.add(t.internalName)
            else -> {}
        }
    }
}