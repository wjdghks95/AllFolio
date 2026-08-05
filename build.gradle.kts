plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.allfolio"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters (BOM 관리 — 버전 명시 불필요)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // DB
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // JWT (Step 3 대비) — BOM 미관리이므로 버전 명시
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    // Structured Logging (Step 7 대비) — BOM 미관리이므로 버전 명시
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.0")
    }
}

tasks.test {
    useJUnitPlatform()
}
