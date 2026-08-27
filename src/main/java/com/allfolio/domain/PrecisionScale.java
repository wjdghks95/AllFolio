package com.allfolio.domain;

/**
 * 「금융 정밀도 규칙」(docs/ROADMAP.md): 코인은 통화 무관 8자리, 그 외엔 통화 기준(KRW 0/USD 4/기타 2).
 * PortfolioService/SimulationService가 각자 복제해 갖고 있던 로직을 통합한다(docs/ROADMAP.md Task 016).
 */
public final class PrecisionScale {

    /** quantity는 자산유형·통화 무관 항상 8자리(GET /v1/portfolio·시뮬레이터 expectedQuantity 공통 규칙). */
    public static final int QUANTITY_SCALE = 8;

    private PrecisionScale() {
    }

    public static int scaleFor(AssetType assetType, String currency) {
        if (assetType == AssetType.COIN) {
            return 8;
        }
        return scaleForCurrency(currency);
    }

    /** 자산 유형과 무관하게 통화 자체의 스케일만 필요한 경우(예: 통화별 합계 집계)에 쓴다. */
    public static int scaleForCurrency(String currency) {
        return switch (currency) {
            case "KRW" -> 0;
            case "USD" -> 4;
            default -> 2;
        };
    }
}
