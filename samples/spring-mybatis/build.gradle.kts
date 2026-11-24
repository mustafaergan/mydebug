plugins { java }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.3"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
    runtimeOnly("com.h2database:h2")
}
tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
