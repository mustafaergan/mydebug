import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.optona.mydebug"
version = providers.gradleProperty("pluginVersion").get()

java {
    // IntelliJ 2026.2 platformu Java 25 bytecode'u ile derlenmiş durumda.
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (!localPath.isNullOrBlank() && file(localPath).exists()) {
            local(localPath)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("com.intellij.java")
        pluginVerifier()
    }
}

intellijPlatform {
    buildSearchableOptions = false
    // GUI Designer formu yok; @NotNull çalışma zamanı denetimine de gerek yok.
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = providers.gradleProperty("changeNotes")
    }
    pluginVerification {
        ides {
            // En eski desteklenen sürüm + yerel güncel IDE.
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("verifyOldestVersion"))
            providers.gradleProperty("platformLocalPath").orNull
                ?.takeIf { file(it).exists() }
                ?.let { local(file(it)) }
        }
    }
    publishing {
        // Token: plugins.jetbrains.com › profil › My Tokens. Repoya yazılmaz, ortam değişkeninden okunur.
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 2024.3+ IDE'ler Java 21 üzerinde çalışır; bayt kodu 21 hedeflenir.
    options.release = 21
}
