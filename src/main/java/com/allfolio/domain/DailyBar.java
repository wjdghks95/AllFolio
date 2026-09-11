package com.allfolio.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 국내 주식 일봉 원본 시계열 1건. 공공데이터포털 getStockPriceInfo는 일별 종가(EOD) 하나만
 * 제공하는 게 아니라 시가({@code mkp})·고가({@code hipr})·저가({@code lopr})·종가({@code clpr})를
 * 함께 제공한다(2026-09-11 curl 실측 확인, {@code .claude/agents/stock-price-api.md} 참고).
 * 거래량 등 그 외 필드는 이 record 범위 밖이다 — 필요해지면 그때 추가한다.
 *
 * <p>주/월/년봉 집계는 이 record가 아니라 이 시계열을 소비하는 서비스 레이어의 책임이다.
 */
public record DailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
