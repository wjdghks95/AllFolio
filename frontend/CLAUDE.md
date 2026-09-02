# 프론트엔드 폴더 규칙

Vite 개발 서버는 `/v1/*` 요청을 `localhost:8080`(Spring Boot)으로 프록시한다. 브라우저에서 같은 출처로 보이므로 개발 중에는 CORS 설정이 필요 없다. 백엔드(`./gradlew bootRun`)와 프론트(`npm run dev`)를 동시에 띄운 상태에서 `http://localhost:5173`으로 접속해 개발한다.
