import java.util.Properties
import java.io.File
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.Roman1233219"
version = "1.2.1"

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
        load(propsFile.inputStream())
    }
}

val studioPath = localProperties.getProperty("intellij.localPath") ?: "D:/Android Studio"

dependencies {
    intellijPlatform {
        local(file(studioPath))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.kotlin")
        
        // Явно запрашиваем JBR для работы JCEF
        jetbrainsRuntime()
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
}

intellijPlatform {
    pluginConfiguration {
        id.set("io.github.Roman1233219.CodeMap")
        name.set("CodeMap")
        version.set(project.version.toString())
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set(null as String?)
        }
        vendor {
            name.set("Roman1233219")
            email.set("codemap.support@gmail.com")
        }
    }
}

tasks {
    named<RunIdeTask>("runIde") {
        // Принудительно включаем нужные флаги в песочнице
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-Didea.kotlin.plugin.use.k2=true",
                    "-Dkotlin.k2.plugin.enabled=true",
                    "-Didea.ignore.disabled.plugins=true",
                    "-Dide.browser.jcef.enabled=true",
                    "-Dide.browser.jcef.sandbox.enabled=false"
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
