package com.allfolio;

import com.allfolio.domain.Price;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Price 값 객체의 accessor와 시세 관련 예외 2종의 메시지 생성자 동작만 확인하는 순수 단위 테스트
 * (docs/ROADMAP.md Task 021 하위 서브태스크).
 */
class PriceTest {

    @Test
    void priceExposesAmountCurrencyAndAsOf() {
        BigDecimal amount = new BigDecimal("123.456");
        Instant asOf = Instant.parse("2026-08-31T00:00:00Z");

        Price price = new Price(amount, "USD", asOf);

        assertThat(price.amount()).isEqualByComparingTo(amount);
        assertThat(price.currency()).isEqualTo("USD");
        assertThat(price.asOf()).isEqualTo(asOf);
    }

    @Test
    void priceUnavailableExceptionKeepsMessage() {
        PriceUnavailableException exception = new PriceUnavailableException("CASH/KRW는 시세 조회 대상이 아닙니다.");

        assertThat(exception.getMessage()).isEqualTo("CASH/KRW는 시세 조회 대상이 아닙니다.");
    }

    @Test
    void externalPriceApiExceptionKeepsMessage() {
        ExternalPriceApiException exception = new ExternalPriceApiException("업비트 API 호출 실패");

        assertThat(exception.getMessage()).isEqualTo("업비트 API 호출 실패");
    }
}
