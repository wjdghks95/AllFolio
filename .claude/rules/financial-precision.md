---
paths:
  - "src/main/java/**/*.java"
---

# 금융 정밀도 (중요)

## BigDecimal 사용

- **원칙:** 금융 계산에는 `double`/`float` 절대 금지. 모든 금액/비율/가중치는 `BigDecimal`.
- **DB:** `NUMERIC(28,8)` ↔ JPA `@Column(columnDefinition = "NUMERIC(28,8)")`로 1:1 매핑
- **스케일 계산:** 코인 8자리 고정, 그 외엔 통화 기준(KRW 0/USD 4/기타 2) 규칙은 `domain/PrecisionScale`에 이미 구현되어 있다. 새 서비스에서 스케일을 다시 계산하지 말고 이 유틸을 재사용할 것 (과거 PortfolioService/SimulationService에 중복 구현됐다가 Task 016에서 통합된 전례가 있음).
- **스케일 전파 주의:** `BigDecimal.subtract()`/`add()` 결과 스케일은 피연산자 중 큰 쪽을 따른다 — 다른 스케일끼리 연산한 뒤 응답으로 내보내기 직전 반드시 `setScale()`로 최종 스케일을 명시할 것(Task 023에서 COIN 손익이 소수 8자리로 잘못 직렬화된 실측 사례 있음).

## 정밀도 검증

```bash
# double 사용 여부 확인 (CI/정적 검사 포함)
grep -r "double " src/main/java --include="*.java" | grep -v "Double\|//.*double"
```
