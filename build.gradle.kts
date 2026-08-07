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
    // Spring Boot 4.1은 Flyway 오토컨피규레이션을 flyway-core가 아닌 별도 모듈로 분리함
    // (org.flywaydb:flyway-core만 있으면 FlywayAutoConfiguration 자체가 로드되지 않음)
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // JWT (Step 3 대비) — BOM 미관리이므로 버전 명시
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    // Structured Logging (Step 7 대비) — BOM 미관리이므로 버전 명시
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Test
    // Testcontainers 버전은 Spring Boot 4.1 BOM이 관리하는 2.x를 그대로 사용한다.
    // (구 1.x를 별도 BOM으로 고정하면 Docker Engine 29+와 API 버전 협상이 깨진다 — Step 2에서 실측 확인)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.test {
    useJUnitPlatform()
}
