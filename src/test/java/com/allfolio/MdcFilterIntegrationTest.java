package com.allfolio;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.allfolio.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 014 — MdcFilter/JwtFilter가 실제로 요청마다 다른 traceId를 발급하고,
 * 인증된 요청에서만 SecurityContext의 userId를 MDC에 채우는지 검증한다.
 *
 * <p>code-reviewer 지적(Major M-1) 이후 MdcFilter를 시큐리티 체인보다 먼저(@Order
 * HIGHEST_PRECEDENCE) 실행하도록 바꾸고 userId는 JwtFilter.authenticate()에서 채우는 방식으로
 * 재설계했다. AuthIntegrationTest의 TestOnlyEndpoints/WhoAmIController 패턴(테스트 소스 전용
 * 컨트롤러, TestTypeExcludeFilter로 다른 테스트 컨텍스트에 안 샘)을 그대로 따른다.
 *
 * <p>앞의 두 테스트(컨트롤러 시점 MDC 확인)만으로는 이 순서가 깨져도 잡지 못한다 — MdcFilter가
 * 시큐리티 체인 뒤에서 실행돼도 컨트롤러에는 이미 traceId/userId가 채워진 채로 도달하기 때문이다
 * (2차 code-reviewer 검증에서 뮤테이션 테스트로 실증: @Order를 제거해도 이 두 테스트는 통과했다).
 * 그래서 securityChainLogsCarryTraceId()가 시큐리티 체인 *내부*(JwtIssuer)의 로그에 traceId가
 * 붙는지를 ListAppender로 직접 확인해, 순서가 뒤바뀌는 회귀를 실제로 잡는다.
 */
@AutoConfigureMockMvc
class MdcFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    @Test
    void authenticatedRequestHasMatchingUserIdInMdc() throws UnsupportedEncodingException {
        String token = accessTokenOf(signup("mdc-probe@example.com", "correct-horse-battery"));

        MvcTestResult result = mvc.get().uri("/v1/test/mdc-userid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String mdcUserId = result.getResponse().getContentAsString();
        assertThat(mdcUserId).isNotBlank();
        assertThat(mdcUserId).isEqualTo(subjectOf(token));
    }

    @Test
    void consecutiveRequestsGetDifferentTraceIds() throws UnsupportedEncodingException {
        String token = accessTokenOf(signup("mdc-probe-2@example.com", "correct-horse-battery"));

        String firstTraceId = mvc.get().uri("/v1/test/mdc-traceid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .getResponse().getContentAsString();
        String secondTraceId = mvc.get().uri("/v1/test/mdc-traceid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .getResponse().getContentAsString();

        assertThat(firstTraceId).isNotBlank();
        assertThat(secondTraceId).isNotBlank();
        assertThat(firstTraceId).isNotEqualTo(secondTraceId);
    }

    @Test
    void securityChainLogsCarryTraceId() {
        var jwtIssuerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("com.allfolio.infra.security.JwtIssuer");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        jwtIssuerLogger.addAppender(appender);

        try {
            // 파싱조차 안 되는 토큰 → JwtIssuer.resolveUserId()가 시큐리티 체인 내부에서
            // "JWT 파싱 실패"를 DEBUG로 남긴다. MdcFilter가 그 시점보다 먼저 실행돼야 이 로그에도
            // traceId가 붙는다.
            mvc.get().uri("/v1/assets").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt").exchange();
        } finally {
            jwtIssuerLogger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).isEqualTo("JWT 파싱 실패");
        assertThat(event.getMDCPropertyMap()).containsKey("traceId");
    }

    private MvcTestResult signup(String email, String password) {
        String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
            return (String) body.get("accessToken");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JWT의 subject(sub) 클레임 = userId. 서명 검증 없이 payload만 읽어 기대값을 구한다. */
    private String subjectOf(String token) {
        String payload = token.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = objectMapper.readValue(json, Map.class);
        return (String) claims.get("sub");
    }

    /**
     * MDC 값 노출 전용 엔드포인트. 테스트 소스에만 존재한다(AuthIntegrationTest.TestOnlyEndpoints와
     * 동일한 이유로 다른 테스트 컨텍스트에 새지 않음).
     */
    @TestConfiguration
    static class TestOnlyEndpoints {

        @Bean
        MdcProbeController mdcProbeController() {
            return new MdcProbeController();
        }
    }

    @RestController
    @RequestMapping("/v1/test")
    static class MdcProbeController {

        @GetMapping("/mdc-userid")
        String userId() {
            return MDC.get("userId");
        }

        @GetMapping("/mdc-traceid")
        String traceId() {
            return MDC.get("traceId");
        }
    }
}
