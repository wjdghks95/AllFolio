package com.allfolio.infra.sse;

import com.allfolio.domain.CandleInterval;

/**
 * COIN 캔들 SSE 구독(Task 028)을 식별하는 키 — 티커+통화+interval 조합. 같은 조합을 보는 구독자는
 * 항상 동일한 데이터를 보므로 {@link CandleSseRegistry}가 구독자 집합과 "마지막 push 값"을 이 키
 * 단위로 관리한다.
 *
 * <p>record라서 구조적 equals/hashCode를 자동 제공해 {@link java.util.concurrent.ConcurrentHashMap}
 * 키로 안전하게 쓸 수 있다 — 문자열 델리미터로 조합·파싱하는 방식(예: {@code ticker + ":" + currency})은
 * 델리미터가 실제 값에 등장할 경우 키 충돌 버그가 생길 수 있어 쓰지 않는다.
 */
public record CandleSubscriptionKey(String ticker, String currency, CandleInterval interval) {
}
