plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.vault"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}


repositories {
    mavenCentral()
}

val awsSdkVersion = "2.29.0"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // AWS SDK v2
    implementation("software.amazon.awssdk:s3:$awsSdkVersion")
    implementation("software.amazon.awssdk:iot:$awsSdkVersion")
    implementation("software.amazon.awssdk:iotdataplane:$awsSdkVersion")
    implementation("software.amazon.awssdk:sts:$awsSdkVersion")

    // SpringDoc OpenAPI (Swagger)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.3")

    // H2 in-memory DB — ใช้ตอน dev ที่ยังไม่มี PostgreSQL
    runtimeOnly("com.h2database:h2")

    // DevTools — auto restart เมื่อ code เปลี่ยน
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// โหลด .env เข้า bootRun อัตโนมัติ
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}

// Compile with Java 25 JDK but target Java 21 class format.
// Required because Spring Boot's Gradle plugin uses ASM which doesn't
// support class file version 69 (Java 25) yet.
tasks.withType<JavaCompile> {
    options.release = 21
}

