package com.allfolio.infra.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Firebase Cloud Messaging(FCM) 서비스 계정 설정. {@code credentialsJson}은 Firebase 콘솔에서 발급받은
 * 서비스 계정 JSON 전체를 한 줄 문자열로 압축해 {@code ALLFOLIO_FCM_CREDENTIALS_JSON} 환경변수로 주입한다.
 *
 * <p>{@code StockProperties.serviceKey}와 동일한 이유로 검증 애너테이션을 붙이지 않는다 — 자격증명이
 * 없어도 앱 부팅은 정상적으로 되어야 하고, 실패는 실제 발송을 시도하는 시점({@link FcmSender})에서만
 * 일어나야 한다(루트 CLAUDE.md 확정 정책, .claude/agents/firebase-fcm.md).
 */
@ConfigurationProperties(prefix = "allfolio.fcm")
public record FcmProperties(String credentialsJson) {
}
