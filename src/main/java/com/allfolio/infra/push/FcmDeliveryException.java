package com.allfolio.infra.push;

/**
 * {@code fcm} Circuit Breaker가 Open 상태이거나 {@link FcmSender#sendToTokens}의 실제 발송 호출이
 * 최종 실패했을 때 던진다. 자격증명 자체가 없어 발송을 시도조차 못하는 {@link FcmNotConfiguredException}과는
 * 구분되는 별도 타입이다 — 이 컴포넌트를 호출하는 서비스가 아직 없어(Task 029 범위 외) 다른 도메인
 * 예외들과 달리 {@code GlobalExceptionHandler} 매핑은 만들지 않는다(실제 호출자가 생기는 후속 태스크에서
 * 처리, {@link FcmNotConfiguredException}과 동일한 방침).
 */
public class FcmDeliveryException extends RuntimeException {

    public FcmDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
