# 도메인 계층 규칙

## 자산 (Asset)

- 종목당 1개 `Holding` (stock/crypto/cash)
- 평단가(`avg_price`), 수량(`quantity`)을 `BigDecimal`로 추적
- 시뮬레이터 입력: 추가 매수 가격 × 수량 → 신규 평단가 계산
- **CASH 자산의 `avg_price`는 항상 `1`** (Task 006 결정). 현금에는 평단가 개념이 없지만 `holdings.avg_price`에 `CHECK (avg_price > 0)` 제약이 있어 `0`을 넣을 수 없다. `1`을 고정값으로 써서 "평가금액 = quantity × avg_price" 계산식을 CASH에도 그대로 적용할 수 있게 한다(문자 그대로 "1원짜리 단위 × 보유량"). 등록 화면에서는 CASH 선택 시 평단가 입력란을 숨긴다
- **타 유저 소유 자원 접근은 403이 아닌 404** — 조회·수정·삭제·시뮬레이터 전 구간의 컨벤션. 소유권 없음과 존재하지 않음을 구분해 응답하면 ID 유출 경로가 되므로, 소유권 조회 쿼리(`findByIdAndUser_Id`)가 없으면 곧장 404로 응답한다

## 포트폴리오 (Portfolio)

- 사용자의 모든 자산 집계 + KRW 기준 비중 계산
- Phase 3(포트폴리오 평가금액·비중·손익, ROADMAP Task 023)부터 평가 손익 추가

## 시뮬레이션

- 요청에 따라 `POST /v1/simulate/avg-price` 호출
- DB 저장 없이 메모리에서 계산 후 반환
- 결과는 "만약 X원에 Y주를 추가 매수하면 평단가는?" 형태

## 물타기 시뮬레이터 (F006) 성능 KPI

- **응답시간 P99:** ≤ 5ms (1,000회 반복 호출)
- **메트릭:** `allfolio.simulation.duration` (Prometheus)로 추적

시뮬레이터는 **DB 쓰기 없음**. 대상 holding을 단건 조회한 뒤 In-Memory에서 가중평균을 계산한다 (조회는 발생하지만 저장은 하지 않는다). P99 목표치는 이 단건 조회를 포함한 수치다. JVM 워밍업 후 검증.

## 낙관적 잠금(Optimistic Lock) 수동 검증 주의

같은 트랜잭션에서 방금 읽은 엔티티의 `version`은 항상 최신값이라, Hibernate의 자동 `@Version` 검사만으로는 "클라이언트가 과거에 읽은 값" 기준 충돌을 잡지 못한다. 갱신 서비스 메서드에서 `entity.getVersion() != request.version()`을 직접 비교해 다르면 `ObjectOptimisticLockingFailureException`을 던질 것(409 응답으로 매핑). 엔티티 갱신 직후 `repository.flush()`를 호출해 응답에 증가된 `version`이 나가도록 보장할 것 (flush 누락 시 응답에 증가 전 값이 나가는 버그 실측됨).
