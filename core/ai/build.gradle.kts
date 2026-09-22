// :core:ai — the agentic engine: multi-protocol LLM transports, SSE streaming, provider adapters,
// model discovery, the ReAct orchestration loop and the tool-calling harness.
// Pure JVM: every byte of this module is unit-testable with no emulator or device.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(project(":core:model"))

    // --- Ktor: streaming HTTP/SSE over a swappable engine ---------------------------------
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.okhttp)
    implementation(libs.okio)

    // --- HTML -> clean text/markdown for the Web Fetch tool -------------------------------
    implementation(libs.jsoup)

    // --- DI --------------------------------------------------------------------------------
    // hilt-core (annotations only), not hilt-android: this module must stay free of the Android
    // platform. The Dagger/Hilt compiler runs through KSP; the Hilt *Gradle* plugin is deliberately
    // not applied here, because it exists for Android bytecode transformation, not for JVM modules.
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)
    compileOnly("javax.inject:javax.inject:1")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
}
