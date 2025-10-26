plugins {
    application
    kotlin("jvm") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    id("com.github.johnrengelman.shadow") version "8.3.5" // <— было 8.1.1
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.interpill.gateway.ApplicationKt")
}

kotlin {
    // Компилируем Kotlin под Java 17 (LTS)
    jvmToolchain(17)
}

java {
    // И Java-код тоже под Java 17
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// На всякий случай фиксируем release для javac
tasks.withType<JavaCompile> {
    options.release.set(17)
}

// И явный target для Kotlin-компилятора
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Тень-джар
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
}

