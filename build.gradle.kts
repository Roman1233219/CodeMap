import java.util.Properties
import java.io.File
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example.codemap"
version = "1.0-SNAPSHOT"

// Возвращаем Java 17 для поддержки более старых IDE
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
        // Если на диске D очень новая Студия (253), она может конфликтовать с Java 17.
        // Для максимальной совместимости лучше указывать версию явно, 
        // но оставим local, раз вы хотите использовать именно её.
        local(file(studioPath))
        
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.example.codemap")
        name.set("CodeMap")
    }
}

tasks {
    patchPluginXml {
        // Устанавливаем поддержку с версии 2023.2 (Java 17)
        sinceBuild.set("232")
        untilBuild.set(null as String?) // Убираем ограничение сверху
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xskip-metadata-version-check")
        }
    }
}
