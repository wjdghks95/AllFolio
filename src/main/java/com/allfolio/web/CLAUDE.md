# 웹 계층 규칙

## 컨트롤러 `@Validated` 금지

클래스 레벨 `@Validated`를 붙이면 Spring 7 내장 메서드 파라미터 검증(400 응답 경로)이 꺼지고 구식 AOP 경로(`ConstraintViolationException`)로 전환되는데, `GlobalExceptionHandler`가 이 예외를 못 잡아 500으로 샌다. `@Min`/`@Max` 등은 `@Validated` 없이도 내장 경로로 그대로 동작하므로 컨트롤러에 붙이지 말 것.

## 커서 페이지네이션 응답 패턴

목록 엔드포인트(`GET /v1/assets`)는 `AssetListResponse`로 래핑한다 — `items: List<AssetResponse>` + `nextCursor: String?`(다음 페이지 없으면 `null`). 서버는 `limit+1`건 조회 후 초과분이 있으면 마지막 항목 id를 Base64 인코딩한 커서를 내보낸다. `GET /v1/assets/search`는 페이지네이션 없이 `List<SearchResultResponse>` 직접 반환(검색 결과는 최대 20건 제한을 외부 API가 담당).

## 에러 응답 포맷

모든 에러는 `GlobalExceptionHandler`가 `{"code": "ERROR_CODE", "message": "..."}` 형태로 통일한다. 새 예외 클래스를 추가할 때는 반드시 `GlobalExceptionHandler`에 매핑을 추가할 것 — 매핑 누락 시 500으로 샌다. 에러 코드 목록은 `docs/ROADMAP.md` 「에러 코드」 절을 따른다.
