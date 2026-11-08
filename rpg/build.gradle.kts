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
