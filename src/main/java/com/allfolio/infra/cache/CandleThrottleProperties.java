package com.allfolio.infra.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 사용자당 COIN 캔들 조회({@code GET /v1/assets/{id}/candles}, COIN 자산) 요청 제한
 * (ROADMAP Task 031 서브태스크 4). 이 경로는 캐시가 전혀 없는 순수 패스스루라 "캐시 히트는 소모하지
 * 않는다"는 PriceThrottle의 전제가 성립하지 않는다 — 진입하는 모든 요청에 균일하게 적용한다.
 *
 * <p>limit=3/window=1s로, 시세 조회용 PriceThrottle(1건/1초)보다 약간 여유를 둔 값이다.
 * 프론트 실제 호출 패턴(AssetDetailPage.tsx)은 자동 폴링이 없고 (a) 화면 진입·자산/interval 전환
 * 시 1회, (b) "이전 구간 더 보기" 클릭 시 1회로, 짧은 시간에 여러 번 호출되는 시나리오는 사용자가
 * 연속으로 interval 버튼을 클릭하는 정도다 — 초당 1건은 그 정상적인 연속 클릭도 429로 막을 수 있어
 * 약간의 여유(3건)를 둔다. 실측 남용 시나리오(30 VUs 동시 요청, 초당 100건 이상)는 이 여유값으로도
 * 충분히 차단된다(loadtest/results.md Task 031 서브태스크 4 참고).
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.candle-throttle")
public record CandleThrottleProperties(
        @Min(1)
        int limit,

        @NotNull
        Duration window
) {
}
