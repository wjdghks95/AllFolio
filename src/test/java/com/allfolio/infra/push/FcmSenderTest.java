package com.allfolio.infra.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FcmSender} 단위 테스트. 실제 서비스 계정 키가 없어(Firebase 프로젝트 미발급) {@link FirebaseMessaging}을
 * Mockito로 목킹해 메시지 조립·실패 응답 파싱 로직만 검증한다(문서 조사 기반, .claude/agents/firebase-fcm.md).
 */
@ExtendWith(MockitoExtension.class)
class FcmSenderTest {

    @Mock
    private FirebaseMessaging messaging;

    @Test
    void sendToTokensCountsSuccessesCorrectly() throws FirebaseMessagingException {
        List<String> tokens = List.of("token-1", "token-2");
        SendResponse success1 = mock(SendResponse.class);
        when(success1.isSuccessful()).thenReturn(true);
        SendResponse success2 = mock(SendResponse.class);
        when(success2.isSuccessful()).thenReturn(true);

        BatchResponse response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of(success1, success2));
        when(response.getSuccessCount()).thenReturn(2);
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(response);

        FcmSender sender = new FcmSender(messaging);
        FcmSendResult result = sender.sendToTokens(tokens, "title", "body", Map.of("assetId", "1"));

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.invalidTokens()).isEmpty();
    }

    @Test
    void sendToTokensCollectsOnlyUnregisteredAndInvalidArgumentAsInvalidTokens() throws FirebaseMessagingException {
        List<String> tokens = List.of("ok-token", "unregistered-token", "invalid-token", "transient-fail-token");

        SendResponse ok = mock(SendResponse.class);
        when(ok.isSuccessful()).thenReturn(true);

        // 각 예외 mock을 별도 문장으로 먼저 완성해둔다 — when(...).thenReturn(exceptionWithCode(...))처럼
        // 진행 중인 stubbing의 인자 위치에서 또 다른 mock()/when()을 중첩 호출하면 Mockito가 바깥쪽
        // stubbing이 끝나지 않은 것으로 오인해 UnfinishedStubbingException을 던진다(실측 확인).
        FirebaseMessagingException unregisteredException = exceptionWithCode(MessagingErrorCode.UNREGISTERED);
        FirebaseMessagingException invalidArgumentException = exceptionWithCode(MessagingErrorCode.INVALID_ARGUMENT);
        FirebaseMessagingException transientException = exceptionWithCode(MessagingErrorCode.UNAVAILABLE);

        SendResponse unregistered = mock(SendResponse.class);
        when(unregistered.isSuccessful()).thenReturn(false);
        when(unregistered.getException()).thenReturn(unregisteredException);

        SendResponse invalidArgument = mock(SendResponse.class);
        when(invalidArgument.isSuccessful()).thenReturn(false);
        when(invalidArgument.getException()).thenReturn(invalidArgumentException);

        SendResponse transientFailure = mock(SendResponse.class);
        when(transientFailure.isSuccessful()).thenReturn(false);
        when(transientFailure.getException()).thenReturn(transientException);

        BatchResponse response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of(ok, unregistered, invalidArgument, transientFailure));
        when(response.getSuccessCount()).thenReturn(1);
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(response);

        FcmSender sender = new FcmSender(messaging);
        FcmSendResult result = sender.sendToTokens(tokens, "title", "body", Map.of());

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.invalidTokens()).containsExactlyInAnyOrder("unregistered-token", "invalid-token");
    }

    /**
     * code-reviewer m4 — 기존 테스트는 {@code sendEachForMulticast(any(MulticastMessage.class))}처럼
     * 인자를 느슨하게 매칭해 어느 필드에 토큰이 실렸는지 전혀 검증하지 않았다. 이 때문에 B1
     * ({@code addAllFids}로 잘못 조립한 버그)이 조용히 통과했다. {@link ArgumentCaptor}로 실제 조립된
     * 메시지를 캡처해, {@code Message.getToken()}(패키지 프라이빗 — firebase-admin 9.10.0 바이트코드로
     * 존재 확인, 리플렉션으로 접근)에 토큰이 채워졌는지 직접 단언한다. {@code addAllFids}로 되돌아가면
     * {@code getToken()}이 전부 {@code null}이 되어 이 테스트가 실패한다.
     */
    @Test
    void sendToTokensPutsTokensInTokenFieldNotFidField() throws Exception {
        List<String> tokens = List.of("token-1", "token-2");
        BatchResponse response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of());
        when(response.getSuccessCount()).thenReturn(0);

        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        when(messaging.sendEachForMulticast(captor.capture())).thenReturn(response);

        FcmSender sender = new FcmSender(messaging);
        sender.sendToTokens(tokens, "title", "body", Map.of());

        assertThat(extractTokens(captor.getValue())).containsExactly("token-1", "token-2");
    }

    /** {@code sendToTokens}가 여전히 {@code fcm} Circuit Breaker에 연결돼 있는지(code-reviewer B2). */
    @Test
    void sendToTokensIsAnnotatedWithFcmCircuitBreaker() throws NoSuchMethodException {
        Method method = FcmSender.class.getMethod("sendToTokens", List.class, String.class, String.class, Map.class);

        CircuitBreaker annotation = method.getAnnotation(CircuitBreaker.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("fcm");
        assertThat(annotation.fallbackMethod()).isEqualTo("fallback");
    }

    /** fallback이 원인 예외를 감싸 {@link FcmDeliveryException}으로 변환하는지(code-reviewer B2). */
    @Test
    void fallbackWrapsCauseInFcmDeliveryException() throws NoSuchMethodException {
        FcmSender sender = new FcmSender(messaging);
        Method fallback = FcmSender.class.getDeclaredMethod(
                "fallback", List.class, String.class, String.class, Map.class, Throwable.class);
        fallback.setAccessible(true);
        RuntimeException cause = new RuntimeException("circuit open");

        assertThatThrownBy(() -> invoke(fallback, sender, List.of("token-1"), "title", "body", Map.of(), cause))
                .isInstanceOf(FcmDeliveryException.class)
                .hasCause(cause);
    }

    /**
     * code-reviewer M1 — {@code FirebaseApp.initializeApp(options)}는 같은 이름(DEFAULT)으로 이미
     * 초기화된 앱이 있으면 {@code IllegalStateException}을 던진다. 실제 서비스 계정 형태를 갖춘(단,
     * 진짜 Google 프로젝트와는 무관한) 자격증명으로 {@code FcmSender}를 두 번 생성해, 두 번째 생성도
     * 예외 없이 기존 앱을 재사용하는지 검증한다.
     */
    @Test
    void constructingTwiceWithSameCredentialsReusesExistingFirebaseApp() throws Exception {
        String credentialsJson = fakeServiceAccountJson();

        assertThatCode(() -> new FcmSender(new FcmProperties(credentialsJson))).doesNotThrowAnyException();
        assertThatCode(() -> new FcmSender(new FcmProperties(credentialsJson))).doesNotThrowAnyException();
    }

    @Test
    void constructingWithoutCredentialsDoesNotThrow() {
        assertThatCode(() -> new FcmSender(new FcmProperties(""))).doesNotThrowAnyException();
    }

    @Test
    void sendToTokensThrowsWhenNotConfigured() {
        FcmSender sender = new FcmSender(new FcmProperties(""));

        assertThatThrownBy(() -> sender.sendToTokens(List.of("token-1"), "title", "body", Map.of()))
                .isInstanceOf(FcmNotConfiguredException.class);
    }

    private static FirebaseMessagingException exceptionWithCode(MessagingErrorCode errorCode) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(errorCode);
        return exception;
    }

    /**
     * {@code MulticastMessage.getMessageList()}/{@code Message.getToken()}은 패키지 프라이빗이라
     * (firebase-admin 9.10.0 바이트코드로 확인, 우리 테스트는 다른 패키지에 있음) 리플렉션으로 우회
     * 접근한다.
     */
    private static List<String> extractTokens(MulticastMessage message) throws Exception {
        Method getMessageList = MulticastMessage.class.getDeclaredMethod("getMessageList");
        getMessageList.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) getMessageList.invoke(message);

        Method getToken = Message.class.getDeclaredMethod("getToken");
        getToken.setAccessible(true);

        List<String> tokens = new ArrayList<>();
        for (Message m : messages) {
            tokens.add((String) getToken.invoke(m));
        }
        return tokens;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * 진짜 Google 프로젝트와 무관하지만 {@code GoogleCredentials.fromStream}이 파싱 가능한 형태의
     * 서비스 계정 JSON을 만든다 — RSA 키페어를 즉석에서 생성해 PKCS8 PEM으로 감싼다(M1 테스트 전용).
     */
    private static String fakeServiceAccountJson() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String escapedPem = privateKeyPem.replace("\n", "\\n");
        return """
                {
                  "type": "service_account",
                  "project_id": "allfolio-test",
                  "private_key_id": "test-key-id",
                  "private_key": "%s",
                  "client_email": "test@allfolio-test.iam.gserviceaccount.com",
                  "client_id": "1234567890",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(escapedPem);
    }
}
