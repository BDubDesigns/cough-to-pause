import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM rules module: no Android dependency is allowed here, so the compiler
// enforces that the authoritative game rules stay platform-independent and JVM-testable.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Same convention as app/core: org.json is part of the Android platform, so it is only
    // compiled against here (bundling it into an APK duplicates platform classes) and supplied
    // explicitly for JVM tests.
    compileOnly("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

// Some JVMs start with `java.io.tmpdir` pointing at a directory that does not exist. The JDK
// packaged for Termux/Android is one of them: its default is a Termux path that is absent on a
// device, and the forked test worker does not inherit the launcher's override. JUnit's
// TemporaryFolder then fails with AccessDeniedException before any test code runs.
//
// Pick a directory the test JVM can actually write to: the environment's TMPDIR when it is a real
// writable directory, otherwise the build JVM's own java.io.tmpdir, otherwise a directory inside
// the module's build output. On a conventional desktop JVM the first usable candidate is the same
// temp directory the JVM would have used anyway, so behaviour there is unchanged.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val candidates = listOfNotNull(
        System.getenv("TMPDIR"),
        System.getProperty("java.io.tmpdir"),
    )
    val writable = candidates
        .map { File(it) }
        .firstOrNull { it.isDirectory && it.canWrite() }
        ?: layout.buildDirectory.dir("tmp/test").get().asFile.also { it.mkdirs() }
    systemProperty("java.io.tmpdir", writable.absolutePath)
}
