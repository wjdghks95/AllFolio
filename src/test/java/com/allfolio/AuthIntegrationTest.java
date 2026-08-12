package com.allfolio;

import com.allfolio.domain.User;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.infra.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 Step 3 — signup/login 흐름과 JWT 보호 경로 검증.
 * 에러 포맷·에러 코드, Security(Access Token 15분)를 대상으로 한다 (PHASE1_PLAN.md Step 3).
 */
@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "trader@example.com";
    private static final String PASSWORD = "correct-horse-battery";

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
    void signupReturnsCreatedWithFifteenMinuteBearerToken() {
        MvcTestResult result = signup(EMAIL, PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.tokenType").asString().isEqualTo("Bearer");
        assertThat(result).bodyJson().extractingPath("$.expiresIn").convertTo(Long.class).isEqualTo(900L);
        assertThat(result).bodyJson().extractingPath("$.accessToken").asString().isNotBlank();
    }

    @Test
    void signupStoresBcryptHashInsteadOfPlaintextPassword() {
        signup(EMAIL, PASSWORD);

        User saved = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void signupWithAlreadyRegisteredEmailReturnsConflict() {
        signup(EMAIL, PASSWORD);

        // 이메일은 소문자로 정규화되므로 대소문자만 다른 가입도 중복으로 걸러야 한다.
        assertThat(signup("Trader@Example.com", PASSWORD))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void signupWithMalformedEmailReturnsValidationError() {
        assertThat(signup("not-an-email", PASSWORD))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void signupWithPasswordShorterThanEightCharactersReturnsValidationError() {
        assertThat(signup(EMAIL, "short7c"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void signupWithMissingPasswordFieldReturnsValidationError() {
        assertThat(post("/v1/auth/signup", "{\"email\":\"%s\"}".formatted(EMAIL)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void signupWithPasswordLongerThanBcryptLimitReturnsValidationErrorNotServerError() {
        // BCrypt는 72바이트 초과 입력에 IllegalArgumentException을 던진다 — 500으로 새지 않는지 확인한다.
        assertThat(signup(EMAIL, "a".repeat(73)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void signupWithMultiByteBcryptLimitPasswordReturnsValidationErrorNotServerError() {
        // 한글 30자 = UTF-8 90바이트. 문자 수(30)는 8~72 범위지만 바이트 수는 72를 초과한다.
        assertThat(signup(EMAIL, "가".repeat(30)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void signupWithUnsupportedHttpMethodReturnsMethodNotAllowed() {
        // POST 전용 경로를 GET으로 호출 — Exception 폴백이 가로채 500으로 뒤바꾸던 회귀 케이스다.
        MvcTestResult result = mvc.get().uri("/v1/auth/signup").exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(bodyOf(result).get("code")).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void signupWithUnsupportedContentTypeReturnsUnsupportedMediaType() {
        MvcTestResult result = mvc.post().uri("/v1/auth/signup")
                .contentType(MediaType.TEXT_PLAIN).content("irrelevant").exchange();

        assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(bodyOf(result).get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void signupWithUnacceptableMediaTypeReturnsNotAcceptable() {
        // JSON 컨버터만 등록돼 있으므로 Accept: text/csv는 406으로 떨어진다.
        // 에러 응답은 클라이언트 Accept와 무관하게 항상 JSON으로 강제된다(handleExceptionInternal의
        // headers.setContentType(APPLICATION_JSON)) — "항상 3필드" 계약(PHASE1_PLAN.md Step 3)을 406에도 보장.
        MvcTestResult result = mvc.post().uri("/v1/auth/signup")
                .header(HttpHeaders.ACCEPT, "text/csv")
                .contentType(MediaType.APPLICATION_JSON).content(credentials(EMAIL, PASSWORD)).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_ACCEPTABLE);
        assertThat(bodyOf(result).get("code")).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void loginWithValidCredentialsReturnsAccessToken() {
        signup(EMAIL, PASSWORD);

        assertThat(post("/v1/auth/login", credentials(EMAIL, PASSWORD)))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.accessToken").asString().isNotBlank();
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() {
        signup(EMAIL, PASSWORD);

        assertThat(post("/v1/auth/login", credentials(EMAIL, "wrong-password")))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginWithUnknownEmailIsIndistinguishableFromWrongPassword() {
        signup(EMAIL, PASSWORD);

        MvcTestResult wrongPassword = post("/v1/auth/login", credentials(EMAIL, "wrong-password"));
        MvcTestResult unknownEmail = post("/v1/auth/login", credentials("ghost@example.com", PASSWORD));

        assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(bodyOf(unknownEmail).get("code")).isEqualTo("INVALID_CREDENTIALS");
        // user enumeration 방지 — 두 응답의 메시지가 같아야 한다.
        assertThat(bodyOf(unknownEmail).get("message")).isEqualTo(bodyOf(wrongPassword).get("message"));
    }

    @Test
    void loginWithUnacceptableMediaTypeStillReturnsJsonErrorBody() {
        // 도메인 예외 경로(InvalidCredentialsException)도 handleExceptionInternal과 동일하게
        // Accept 헤더와 무관하게 JSON 본문을 강제해야 한다 (M-1 회귀 방지).
        signup(EMAIL, PASSWORD);

        MvcTestResult result = mvc.post().uri("/v1/auth/login")
                .header(HttpHeaders.ACCEPT, "text/csv")
                .contentType(MediaType.APPLICATION_JSON).content(credentials(EMAIL, "wrong-password")).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(bodyOf(result).get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorized() {
        assertThat(mvc.get().uri("/v1/assets").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithValidTokenPassesAuthentication() {
        String token = accessTokenOf(signup(EMAIL, PASSWORD));

        // /v1/assets 컨트롤러는 Step 4에서 생긴다. 401이 아닌 404라는 것이 곧 인증 통과의 증거다.
        assertThat(authorizedGet("/v1/assets", "Bearer " + token))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpointWithTamperedSignatureReturnsUnauthorized() {
        String token = accessTokenOf(signup(EMAIL, PASSWORD));
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "dGFtcGVyZWQtc2lnbmF0dXJl";

        assertThat(authorizedGet("/v1/assets", "Bearer " + tampered))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithExpiredTokenReturnsUnauthorized() {
        // 서명은 유효하되 exp만 과거인 토큰 — nimbus가 만료를 자동 검사하지 않으므로 반드시 필요한 케이스다.
        JwtIssuer expiredIssuer = new JwtIssuer(new JwtProperties(TEST_JWT_SECRET, Duration.ofMinutes(-15)));
        String expired = expiredIssuer.issue(UUID.randomUUID());

        assertThat(authorizedGet("/v1/assets", "Bearer " + expired))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithUnsecuredAlgNoneTokenReturnsUnauthorized() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"%s\",\"exp\":%d}"
                .formatted(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond()));
        String algNone = header + "." + payload + ".";

        assertThat(authorizedGet("/v1/assets", "Bearer " + algNone))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void authorizationHeaderWithoutBearerPrefixReturnsUnauthorized() {
        String token = accessTokenOf(signup(EMAIL, PASSWORD));

        assertThat(authorizedGet("/v1/assets", token))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void authenticatedRequestExposesUserIdAsSecurityPrincipal() {
        String token = accessTokenOf(signup(EMAIL, PASSWORD));
        UUID userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();

        assertThat(authorizedGet("/v1/test/whoami", "Bearer " + token))
                .hasStatusOk()
                .hasBodyTextEqualTo(userId.toString());
    }

    @Test
    void actuatorHealthIsAccessibleAnonymously() {
        assertThat(mvc.get().uri("/actuator/health").exchange()).hasStatusOk();
    }

    @Test
    void actuatorPrometheusIsAccessibleAnonymously() {
        assertThat(mvc.get().uri("/actuator/prometheus").exchange()).hasStatusOk();
    }

    private MvcTestResult signup(String email, String password) {
        return post("/v1/auth/signup", credentials(email, password));
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult authorizedGet(String uri, String authorizationHeader) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, authorizationHeader).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SecurityContext 주입 확인 전용 엔드포인트. 테스트 소스에만 존재한다.
     * Boot의 TestTypeExcludeFilter가 테스트 클래스의 중첩 클래스를 컴포넌트 스캔에서 제외하므로
     * 다른 테스트 컨텍스트로 새지 않는다.
     *
     * <p>@SpringBootTest가 중첩 @TestConfiguration을 자동 등록하므로 @Import를 따로 붙이지 않는다
     * (둘 다 쓰면 설정 클래스가 2번 등록돼 "Ambiguous mapping"으로 컨텍스트 기동이 실패한다).
     */
    @TestConfiguration
    static class TestOnlyEndpoints {

        @Bean
        WhoAmIController whoAmIController() {
            return new WhoAmIController();
        }
    }

    /**
     * TestOnlyEndpoints 안에 중첩하면 안 된다 — @Component 계열 중첩 멤버 클래스는
     * ConfigurationClassParser가 imported configuration으로도 등록해 @Bean과 중복되고
     * "Ambiguous mapping"으로 컨텍스트가 죽는다.
     *
     * <p>Spring Framework 7.0의 RequestMappingHandlerMapping.isHandler()는 @Controller만 인정한다
     * (타입 레벨 @RequestMapping 단독 인식은 제거됨) — @RestController가 필수다.
     */
    @RestController
    @RequestMapping("/v1/test")
    static class WhoAmIController {

        @GetMapping("/whoami")
        String whoAmI(@AuthenticationPrincipal String userId) {
            return userId;
        }
    }
}
