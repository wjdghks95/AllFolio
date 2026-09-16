package com.allfolio.infra.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Firebase Cloud Messaging(FCM) 발송 계층(Task 029). 무엇을 언제 보낼지(등락률 계산·임계값 교차 판단·
 * 중복 발송 방지)는 이 컴포넌트의 책임이 아니다 — 이미 결정된 메시지를 실제 기기 토큰으로 전달하는
 * 역할만 담당한다(.claude/agents/firebase-fcm.md 참고). 이 세션에는 이 컴포넌트를 호출하는
 * 서비스·엔드포인트·스케줄러가 없다 — 후속 태스크 몫이다.
 *
 * <p><b>자격증명 미설정 시 정책</b>: {@code ALLFOLIO_STOCK_SERVICE_KEY}와 동일하게 "미설정 시 부팅은
 * 성공하고, 실제 발송 시점에만 실패"하는 관대한 패턴을 따른다({@code ALLFOLIO_JWT_SECRET}처럼 부팅
 * 자체를 막는 패턴과 다름 — 사용자 확정, 2026-09-16). 자격증명이 비어 있으면 {@link FirebaseMessaging}을
 * {@code null}로 두고, {@link #sendToTokens}가 호출되는 시점에만 {@link FcmNotConfiguredException}을
 * 던진다.
 *
 * <p><b>문서 조사 기반</b> — 실제 서비스 계정 키로 검증되지 않았다(Firebase 프로젝트 미발급). Mockito로
 * {@link FirebaseMessaging}을 목킹한 단위 테스트로 메시지 조립·실패 응답 파싱 로직만 검증했다.
 */
@Component
public class FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);

    // FCM Admin SDK는 RestClient 기반이 아니라 spring.http.clients(connect-timeout/read-timeout)
    // 자동 설정이 적용되지 않는다 — FirebaseOptions.Builder에 동일한 값을 직접 명시해야 한다
    // (code-reviewer B2, firebase-admin 9.10.0 바이트코드로 setConnectTimeout/setReadTimeout 존재 확인).
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 3_000;

    private final FirebaseMessaging messaging;

    // Spring이 다음 패키지 전용(테스트용) 생성자와 헷갈리지 않도록 명시적으로 이 생성자를 지정한다
    // (두 생성자 모두 인자 1개라 @Autowired 없이는 NoSuchMethodException으로 컨텍스트 로딩이 실패한다).
    @Autowired
    public FcmSender(FcmProperties properties) {
        this.messaging = initMessaging(properties.credentialsJson());
    }

    /** 테스트 전용 — 자격증명 초기화를 건너뛰고 {@link FirebaseMessaging}을 직접 주입한다. */
    FcmSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    private static FirebaseMessaging initMessaging(String credentialsJson) {
        if (credentialsJson == null || credentialsJson.isBlank()) {
            log.warn("ALLFOLIO_FCM_CREDENTIALS_JSON이 설정되지 않았습니다 — FCM 발송 기능이 비활성화됩니다.");
            return null;
        }
        try {
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                    .setReadTimeout(READ_TIMEOUT_MILLIS)
                    .build();
            return FirebaseMessaging.getInstance(resolveFirebaseApp(options));
        } catch (IOException e) {
            log.warn("FCM 자격증명 초기화에 실패했습니다 — FCM 발송 기능이 비활성화됩니다.", e);
            return null;
        }
    }

    /**
     * {@code FirebaseApp.initializeApp(options)}는 같은 이름(기본값 DEFAULT)으로 이미 초기화된 앱이
     * 있으면 {@code IllegalStateException("... already exists!")}을 던진다 — 같은 JVM에서 여러
     * {@code @SpringBootTest} 컨텍스트가 뜨거나 devtools 재시작이 겹치는 상황에서 재현된다
     * (code-reviewer M1). {@link FirebaseApp#getApps()}로 먼저 기존 앱 존재 여부를 확인해 재사용하고,
     * 그 확인과 초기화 사이의 경합으로 여전히 예외가 나면 방어적으로 다시 조회해 재사용한다 — named
     * app을 새로 만드는 방식 대신 DEFAULT 앱을 재사용하는 쪽을 택한 이유는, 이 프로젝트에 FirebaseApp을
     * 쓰는 컴포넌트가 FcmSender 하나뿐이라 이름을 분리할 실익이 없고, 재사용이 "이미 초기화된 자격증명을
     * 유지"라는 FirebaseApp의 기존 시맨틱과도 자연스럽게 맞기 때문이다.
     */
    private static FirebaseApp resolveFirebaseApp(FirebaseOptions options) {
        for (FirebaseApp existing : FirebaseApp.getApps()) {
            if (FirebaseApp.DEFAULT_APP_NAME.equals(existing.getName())) {
                return existing;
            }
        }
        try {
            return FirebaseApp.initializeApp(options);
        } catch (IllegalStateException alreadyInitialized) {
            return FirebaseApp.getInstance(FirebaseApp.DEFAULT_APP_NAME);
        }
    }

    /**
     * 동일한 알림 내용을 여러 기기 토큰으로 발송한다. Virtual Threads 활성 프로젝트라 {@code ApiFuture}
     * 기반 비동기 API 대신 동기 {@code sendEachForMulticast}를 그대로 쓴다(.claude/rules/spring-boot-4.md,
     * `UpbitPriceClient`/`StockPriceClient`와 동일한 동기 호출 패턴).
     *
     * @throws FcmNotConfiguredException 자격증명이 설정되지 않아 발송 자체가 불가능한 경우
     */
    @SuppressWarnings("deprecation") // addAllTokens는 firebase-admin 9.10.0에서 deprecated됐지만, 우리가
    // 다루는 값(device_tokens.token = FCM 등록 토큰)에 맞는 API는 여전히 이것이다 — addAllFids는
    // Firebase Installation ID(FID)라는 다른 개념을 위한 것으로, 등록 토큰을 여기 넣으면 발송이
    // 100% 실패한다(code-reviewer B1, firebase-admin 9.10.0 바이트코드로 Message.Builder.setToken vs
    // setFid가 서로 다른 필드로 컴파일되는 것 확인).
    @CircuitBreaker(name = "fcm", fallbackMethod = "fallback")
    public FcmSendResult sendToTokens(List<String> tokens, String title, String body, Map<String, String> data)
            throws FirebaseMessagingException {
        if (messaging == null) {
            throw new FcmNotConfiguredException("FCM 자격증명이 설정되지 않아 발송할 수 없습니다.");
        }

        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .build();

        BatchResponse response = messaging.sendEachForMulticast(message);

        List<SendResponse> responses = response.getResponses();
        List<String> invalidTokens = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (sendResponse.isSuccessful() || sendResponse.getException() == null) {
                continue;
            }
            MessagingErrorCode errorCode = sendResponse.getException().getMessagingErrorCode();
            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                invalidTokens.add(tokens.get(i));
            }
        }

        return new FcmSendResult(response.getSuccessCount(), invalidTokens);
    }

    /**
     * {@code fcm} Circuit Breaker가 Open 상태이거나 {@code sendEachForMulticast} 호출 자체가 실패했을
     * 때 호출된다(code-reviewer B2 — 이 저장소의 다른 외부 API 클라이언트와 동일한 CB 컨벤션).
     * {@code FcmNotConfiguredException}은 {@code application.yml}의 {@code ignore-exceptions}에 등록돼
     * 있어 이 fallback을 거치지 않고 그대로 전파된다 — 자격증명 미설정은 외부 서비스 장애가 아니라
     * 설정 문제이기 때문이다({@code TickerNotFoundException}을 CB 실패 집계에서 제외하는 기존
     * 패턴과 동일, `infra/price/CLAUDE.md`).
     */
    private FcmSendResult fallback(List<String> tokens, String title, String body, Map<String, String> data,
            Throwable ex) {
        log.warn("FCM 발송에 실패했습니다({}건 대상): {}", tokens.size(), ex.getMessage());
        throw new FcmDeliveryException("FCM 발송에 실패했습니다.", ex);
    }
}
