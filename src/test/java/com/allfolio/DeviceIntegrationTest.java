package com.allfolio;

import com.allfolio.domain.repository.DeviceTokenRepository;
import com.allfolio.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 029 — 기기 토큰 등록/해제 REST API 검증. AssetIntegrationTest의
 * MockMvcTester 컨벤션(authorizedXxx 헬퍼, bodyOf)을 그대로 따른다.
 */
@AutoConfigureMockMvc
class DeviceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        deviceTokenRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("device-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("device-b@example.com", "correct-horse-battery"));
    }

    @Test
    void registerDeviceReturnsCreatedWithoutTokenField() {
        MvcTestResult result = registerDevice(tokenA, "fcm-token-1", "ANDROID");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("id")).isNotNull();
        assertThat(body.get("platform")).isEqualTo("ANDROID");
        assertThat(body.get("createdAt")).isNotNull();
        assertThat(body).doesNotContainKey("token");
    }

    @Test
    void reRegisteringSameTokenBySameUserReactivatesInsteadOfCreatingNewRow() {
        String firstId = idOf(registerDevice(tokenA, "fcm-token-2", "ANDROID"));

        String secondId = idOf(registerDevice(tokenA, "fcm-token-2", "ANDROID"));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(deviceTokenRepository.findByToken("fcm-token-2")).isPresent();
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void registeringSameTokenByDifferentUserInvalidatesOldRegistrationAndCreatesNewOne() {
        String oldId = idOf(registerDevice(tokenA, "fcm-token-3", "ANDROID"));

        String newId = idOf(registerDevice(tokenB, "fcm-token-3", "IOS"));

        assertThat(newId).isNotEqualTo(oldId);
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
        assertThat(deviceTokenRepository.findByToken("fcm-token-3")).hasValueSatisfying(
                dt -> assertThat(dt.getUser().getId().toString()).isNotNull());

        // 이전 등록은 통째로 무효화됐으므로 원래 소유자(A)가 옛 id로 해제를 시도하면 404여야 한다.
        assertThat(authorizedDelete("/v1/devices/" + oldId, tokenA))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("DEVICE_NOT_FOUND");
    }

    @Test
    void revokingOwnDeviceReturnsNoContent() {
        String deviceId = idOf(registerDevice(tokenA, "fcm-token-4", "WEB"));

        assertThat(authorizedDelete("/v1/devices/" + deviceId, tokenA)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void revokingOtherUsersDeviceReturnsDeviceNotFound() {
        String deviceId = idOf(registerDevice(tokenA, "fcm-token-5", "IOS"));

        assertThat(authorizedDelete("/v1/devices/" + deviceId, tokenB))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("DEVICE_NOT_FOUND");
    }

    @Test
    void registeringWithTokenExceedingColumnLengthReturnsValidationError() {
        String oversizedToken = "a".repeat(4097);

        assertThat(registerDevice(tokenA, oversizedToken, "ANDROID"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void registeringWithoutAuthenticationReturnsUnauthorized() {
        MvcTestResult result = mvc.post().uri("/v1/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deviceRequest("fcm-token-6", "ANDROID"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult signup(String email, String password) {
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    private MvcTestResult registerDevice(String authToken, String deviceToken, String platform) {
        return mvc.post().uri("/v1/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deviceRequest(deviceToken, platform))
                .exchange();
    }

    private MvcTestResult authorizedDelete(String uri, String authToken) {
        return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    private String idOf(MvcTestResult result) {
        return (String) bodyOf(result).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String deviceRequest(String token, String platform) {
        return """
                {"token":"%s","platform":"%s"}
                """.formatted(token, platform);
    }
}
