import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":core"))
    api(project(":transport"))
    implementation(libs.kotlinx.coroutines.core)
    // X.509 certificate generation for the JVM identity store. TLS itself is
    // provided by the JDK (SSLEngine); no custom cryptography is implemented here.
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(project(":protocol"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "512m"
    maxParallelForks = 1
    timeout.set(Duration.ofMinutes(5))
}
