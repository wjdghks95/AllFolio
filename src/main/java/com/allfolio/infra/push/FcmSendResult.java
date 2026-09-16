package com.allfolio.infra.push;

import java.util.List;

/**
 * {@link FcmSender#sendToTokens} 결과. {@code invalidTokens}는 {@code UNREGISTERED}/
 * {@code INVALID_ARGUMENT}로 실패해 다시는 유효해지지 않는 토큰만 담는다 — 그 외 실패(일시적 오류 등)는
 * 포함되지 않는다. {@code device_tokens} 정리는 이 값을 넘겨받는 호출자(senior-backend 소관) 책임이다.
 */
public record FcmSendResult(int successCount, List<String> invalidTokens) {
}
