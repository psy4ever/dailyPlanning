plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}
group = "com.dayoudu"
version = "0.3.2"
repositories { mavenCentral() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core:11.20.3")
    runtimeOnly("org.flywaydb:flyway-mysql:11.20.3")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform(); inputs.property("databaseUrl", System.getenv("SPRING_DATASOURCE_URL") ?: "h2") }
tasks.bootJar { archiveFileName.set("daily-rhythm.jar") }
// Test-only loopback server for emulator integration; test resources are not packaged.
tasks.register<JavaExec>("smokeServer") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.dayoudu.cloud.CloudApplication")
    args("--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=18080")
}
