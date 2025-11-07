package tool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import com.github.ajalt.clikt.parameters.types.path
import java.io.File

class Minify : CliktCommand(name = "jvm-minify", help = "Minify a JVM classpath to the declarations reachable from root signatures.") {
    private val classpath by option("--classpath", "-cp", help = "Classpath JARs, ':' (Unix) or ';' (Windows) separated").required()
    private val roots by option("--roots", help = "Path to file with root signatures").path(mustExist = true).required()
    private val output by option("--output", "-o", help = "Output JAR path").path().required()

    override fun run() {
        val sep = File.pathSeparator
        val jars = classpath.split(sep).map { Paths.get(it) }
        echo("Classpath: ${jars.size} jars; first wins for duplicates")
        val cp = ClasspathIndex(jars)

        val rootsParsed = roots.readLines().mapNotNull(SigParser::parse)
        echo("Roots: ${rootsParsed.size}")

        val dep = DependencyCollector(cp)
        dep.seed(rootsParsed)

        echo("Retained: ${dep.keepClasses.size} classes, ${dep.keepFields.size} fields, ${dep.keepMethods.size} methods")

        val emitter = Emitter(cp)
        emitter.emit(dep.keepClasses, dep.keepFields, dep.keepMethods, output)
        echo("Wrote ${output}")
    }
}

fun main(args: Array<String>) = Minify().main(args)