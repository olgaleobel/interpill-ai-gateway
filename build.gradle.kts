plugins {
    application
    kotlin("jvm") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

kotlin {
    // Компилируем Kotlin под Java 21
    jvmToolchain(21)
}

java {
    // Компилируем Java-код под Java 21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    // Гарантируем target 21 даже если локальный JDK новее
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    // Точка входа
    mainClass.set("com.interpill.gateway.ApplicationKt")
}

dependencies {
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    // (опционально) если нужен напрямую JSON
    // implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

// Тень-джар с зависимостями
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("interpill-ai-gateway")
    archiveVersion.set("")           // без версии в имени файла
    archiveClassifier.set("all")     // ...-all.jar
    mergeServiceFiles()
    minimize()
}

// На всякий: если кто-то вызовет обычный jar — пусть падассемблится тень-джар
tasks.build {
    dependsOn(tasks.shadowJar)
}

