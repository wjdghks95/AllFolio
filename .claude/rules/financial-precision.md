---
paths:
  - "src/main/java/**/*.java"
---

# 금융 정밀도 (중요)

## BigDecimal 사용

- **원칙:** 금융 계산에는 `double`/`float` 절대 금지. 모든 금액/비율/가중치는 `BigDecimal`.
- **DB:** `NUMERIC(28,8)` ↔ JPA `@Column(columnDefinition = "NUMERIC(28,8)")`로 1:1 매핑
- **스케일 계산:** 코인 8자리 고정, 그 외엔 통화 기준(KRW 0/USD 4/기타 2) 규칙은 `domain/PrecisionScale`에 이미 구현되어 있다. 새 서비스에서 스케일을 다시 계산하지 말고 이 유틸을 재사용할 것 (과거 PortfolioService/SimulationService에 중복 구현됐다가 Task 016에서 통합된 전례가 있음).

## 정밀도 검증

```bash
# double 사용 여부 확인 (CI/정적 검사 포함)
grep -r "double " src/main/java --include="*.java" | grep -v "Double\|//.*double"
```
