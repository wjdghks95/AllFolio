# 1단계: 프론트엔드 빌드 — package*.json만 먼저 복사해 소스 변경 시에도 npm ci 레이어를 재사용한다.
FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# frontend/.env는 이미지에 없으므로 VITE_API_BASE_URL 미정의 -> apiBase.ts가 '' 로 처리해 상대경로 유지
RUN npm run build

# 2단계: 백엔드 빌드 — 래퍼/설정을 먼저 복사해 의존성 다운로드 레이어를 소스 변경과 분리한다.
FROM eclipse-temurin:25-jdk AS backend-build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null || true
COPY src/ src/
# 프론트 산출물은 위 단계에서 이미 만들었으므로 frontendBuild(npm 중복 실행)만 건너뛰고, dist->static 복사는 유지한다.
COPY --from=frontend-build /frontend/dist frontend/dist
RUN ./gradlew bootJar --no-daemon -x frontendBuild -x test

# 3단계: 실행 이미지 — JRE만 포함. 시크릿은 굽지 않고 런타임 -e 로만 주입한다.
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
# -plain.jar 제외하고 실행 가능한 JAR만 복사
COPY --from=backend-build /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
