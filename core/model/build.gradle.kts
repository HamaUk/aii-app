// :core:model — the domain vocabulary of the agent platform (conversations, messages, providers,
// agent events, tool contracts). Framework-free: no Room, no Compose, no Ktor.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}
