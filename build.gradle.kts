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

    // Circuit Breaker (Task 021 대비) — Spring Boot 4용 아티팩트가 최근에야 나와 BOM 미관리, 버전 명시
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("org.springframework.boot:spring-boot-restclient")

    // Redis 캐시·Throttling (Task 022) — Lettuce가 기본 클라이언트, BOM 관리 대상이라 버전 명시 불필요
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // FCM 발송 (Task 029) — BOM 미관리, 버전 명시. Maven Central 최신 안정판(2026-09-16 확인) 사용
    implementation("com.google.firebase:firebase-admin:9.10.0")

    // Test
    // Testcontainers 버전은 Spring Boot 4.1 BOM이 관리하는 2.x를 그대로 사용한다.
    // (구 1.x를 별도 BOM으로 고정하면 Docker Engine 29+와 API 버전 협상이 깨진다 — Step 2에서 실측 확인)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4부터 @SpringBootTest가 MockMvc를 더 이상 자동 제공하지 않는다.
    // @AutoConfigureMockMvc가 org.springframework.boot.webmvc.test.autoconfigure 패키지로 이동했고
    // starter-test에는 포함되지 않으므로 별도 스타터가 필요하다 (Step 3에서 실측 확인).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")

    // 외부 시세 API Mock (Task 021 대비) — BOM 미관리, 버전 명시. 베타(4.0.0-beta.x)가 아닌 최신 안정판 사용
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}

// 프론트엔드(Vite) 빌드 산출물을 jar의 static/ 으로 통합한다 (단일 배포 아티팩트).
// VITE_API_BASE_URL은 일부러 넘기지 않는다 — frontend/.env의 빈 값을 그대로 써서 상대경로(same-origin) 호출을 유지한다.
// -PskipFrontend 를 주면 npm 빌드/복사를 건너뛴다(백엔드만 반복 빌드할 때 opt-out).
val skipFrontend = providers.gradleProperty("skipFrontend").isPresent

val frontendBuild by tasks.registering(Exec::class) {
    enabled = !skipFrontend
    workingDir = file("frontend")
    commandLine("sh", "-c", "npm ci && npm run build")
    // 입력/출력을 선언해 프론트 변경이 없으면 up-to-date 로 건너뛴다(테스트마다 npm ci 방지).
    inputs.dir("frontend/src")
    inputs.dir("frontend/public")
    inputs.files(fileTree("frontend") {
        include("index.html", "package.json", "package-lock.json", "tsconfig*.json", "vite.config.ts", ".env*")
    })
    outputs.dir("frontend/dist")
}

tasks.processResources {
    if (!skipFrontend) {
        dependsOn(frontendBuild)
        // 소스 트리(src/main/resources)는 건드리지 않고 빌드 출력에만 복사한다.
        from("frontend/dist") {
            into("static")
        }
    }
}
