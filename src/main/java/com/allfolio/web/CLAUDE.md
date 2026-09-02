# 웹 계층 규칙

## 컨트롤러 `@Validated` 금지

클래스 레벨 `@Validated`를 붙이면 Spring 7 내장 메서드 파라미터 검증(400 응답 경로)이 꺼지고 구식 AOP 경로(`ConstraintViolationException`)로 전환되는데, `GlobalExceptionHandler`가 이 예외를 못 잡아 500으로 샌다. `@Min`/`@Max` 등은 `@Validated` 없이도 내장 경로로 그대로 동작하므로 컨트롤러에 붙이지 말 것.
