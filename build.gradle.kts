import java.util.Properties
import java.io.File
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example.codemap"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

val localProperties = Properties().apply {
    val propsFile = File(projectDir, "local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

val studioPath = localProperties.getProperty("intellij.localPath") ?: "D:/Android Studio"

dependencies {
    intellijPlatform {
        local(file(studioPath))
        
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.kotlin")
        
        instrumentationTools()
    }
    
    // Добавляем стандартные библиотеки Kotlin для компиляции
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.example.codemap")
        name.set("CodeMap")
        version.set("1.0.0")
        
        ideaVersion {
            // Устанавливаем 253 согласно вашей версии IDE (2025.3 Panda)
            sinceBuild.set("253")
            untilBuild.set(null as String?)
        }

        vendor {
            name.set("Example Vendor")
            email.set("support@example.com")
        }
    }
    instrumentCode.set(true)
}

tasks {
    named<RunIdeTask>("runIde") {
        // Форсируем режим K2 и обходим проверку совместимости
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-Didea.kotlin.plugin.use.k2=true",
                    "-Dkotlin.k2.plugin.enabled=true",
                    "-Didea.ignore.disabled.plugins=true"
                )
            }
        )
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}
