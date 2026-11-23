plugins {
    java
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Ajan hedef uygulamanın JVM'inde çalışır; Java 8 uygulamaları da desteklenir.
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    archiveFileName = "mydebug-agent.jar"
    manifest {
        attributes(
            "Premain-Class" to "com.optona.mydebug.agent.MyDebugAgent",
            "Agent-Class" to "com.optona.mydebug.agent.MyDebugAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "false",
        )
    }
}
