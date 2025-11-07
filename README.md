# JVM Dependency Minifier

A Kotlin CLI that minifies a set of JVM libraries down to only the declarations reachable from a set of root signatures.

## Features
- Classpath **first-wins** precedence for duplicate classes.
- Roots file supports classes, fields, and methods (same descriptors as class files, `#` separator).
- Recursively collects dependencies from types, generics, annotations, throws, records, sealed hierarchy, nest/enclosing metadata.
- Preserves annotations.
- Trims method bodies and static initializers; emits trivial bodies.
- Prunes Kotlin `@Metadata` using `kotlinx-metadata-jvm`.
- Graceful handling of missing references.

## Build

```bash
./gradlew shadowJar
```

The runnable fat-jar will be at `build/libs/jvm-minify-all.jar`.

## Run

```bash
java -jar build/libs/jvm-minify-all.jar   --classpath path/to/a.jar:path/to/b.jar:path/to/c.jar   --roots roots.txt   --output out/minified.jar
```
On Windows, use `;` instead of `:` for the classpath separator.
