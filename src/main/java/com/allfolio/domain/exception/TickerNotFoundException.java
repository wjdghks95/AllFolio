package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * {@link ExternalPriceApiException}의 하위 타입이라 응답 상태·코드(503 EXTERNAL_API_DOWN)는 동일하지만,
 * 원인이 외부 서비스 장애가 아니라 "요청한 티커에 해당하는 종목/마켓이 없음"(정상 200 응답, 매칭 실패)이라는
 * 점에서 구분한다. 이 구분이 필요한 이유는 Circuit Breaker(`application.yml`의 stock/upbit 인스턴스)가
 * `ignore-exceptions`로 이 타입만 실패 집계에서 제외하기 위함이다 — 존재하지 않는 티커 조회는 클라이언트
 * 입력 오류이지 외부 의존성 장애가 아니므로, 반복돼도 Circuit Breaker를 Open시켜서는 안 된다.
 */
public class TickerNotFoundException extends ExternalPriceApiException {

    public TickerNotFoundException(String message) {
        super(message);
    }
}
