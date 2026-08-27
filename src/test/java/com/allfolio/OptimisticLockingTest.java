package com.allfolio;

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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낙관적 잠금(docs/ROADMAP.md Task 016)이 두 요청을 동시에 제출해도 정확히 1건만 성공시키는지
 * 검증한다. 기존 AssetIntegrationTest.updateHoldingWithStaleVersionReturnsHoldingConflict는 첫
 * PUT을 성공시킨 뒤 같은 version으로 두 번째 PUT을 보내는 순차 재현이라, 두 요청을 동시에
 * 제출하는 경로 자체는 검증하지 않았다.
 *
 * <p>주의(code-reviewer 지적): AssetService.updateHolding()은 flush 이전에 인메모리로
 * {@code holding.getVersion() != request.version()}을 먼저 비교하므로, 이 테스트는 두 스레드가
 * DB 커밋 시점까지 실제로 겹치는지와 무관하게 통과한다 — "동시 제출 시 정확히 1건만 성공"을
 * 증명할 뿐, "DB 트랜잭션이 실제로 경합했는지"는 증명하지 않는다. @Version 자체가 제거되면
 * 두 요청 모두 200이 되어 실패하므로 낙관적 잠금 보호 자체에 대한 검출력은 있다.
 */
@AutoConfigureMockMvc
class OptimisticLockingTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private String assetId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        token = accessTokenOf(signup("trader@example.com", "correct-horse-battery"));
        assetId = idOf(createAsset(token, stockRequest("005930", "삼성전자", "10", "60000")));
    }

    @Test
    void concurrentUpdatesWithSameVersionResultInExactlyOneConflict() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcTestResult> requestA = concurrentUpdate(startLatch, "20", "70000");
            Callable<MvcTestResult> requestB = concurrentUpdate(startLatch, "30", "80000");

            Future<MvcTestResult> futureA = executor.submit(requestA);
            Future<MvcTestResult> futureB = executor.submit(requestB);
            startLatch.countDown();

            MvcTestResult resultA = futureA.get(10, TimeUnit.SECONDS);
            MvcTestResult resultB = futureB.get(10, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(
                    resultA.getResponse().getStatus(), resultB.getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());

            MvcTestResult conflictResult = resultA.getResponse().getStatus() == HttpStatus.CONFLICT.value()
                    ? resultA : resultB;
            assertThat(bodyOf(conflictResult).get("code")).isEqualTo("HOLDING_CONFLICT");

            MvcTestResult winner = resultA.getResponse().getStatus() == HttpStatus.OK.value() ? resultA : resultB;
            Map<String, Object> winnerBody = bodyOf(winner);
            assertThat(winnerBody.get("version")).isEqualTo(1);

            MvcTestResult refetched = authorizedGet("/v1/assets/" + assetId, token);
            Map<String, Object> refetchedBody = bodyOf(refetched);
            assertThat(refetchedBody.get("version")).isEqualTo(1);
            // PUT 응답은 요청 스케일 그대로("30"), GET은 DB NUMERIC(28,8) 왕복 스케일("30.00000000")로
            // 표기가 다르다(docs/ROADMAP.md Task 012 「남은 갭」) — 문자열이 아닌 수치로 비교한다.
            assertThat(new BigDecimal((String) refetchedBody.get("quantity")))
                    .isEqualByComparingTo(new BigDecimal((String) winnerBody.get("quantity")));
            assertThat(new BigDecimal((String) refetchedBody.get("avgPrice")))
                    .isEqualByComparingTo(new BigDecimal((String) winnerBody.get("avgPrice")));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<MvcTestResult> concurrentUpdate(CountDownLatch startLatch, String quantity, String avgPrice) {
        return () -> {
            startLatch.await();
            return authorizedPut("/v1/assets/" + assetId + "/holdings", token,
                    updateHoldingRequest(quantity, avgPrice, 0));
        };
    }

    private MvcTestResult signup(String email, String password) {
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    private MvcTestResult createAsset(String token, String body) {
        return mvc.post().uri("/v1/assets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult authorizedGet(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult authorizedPut(String uri, String token, String body) {
        return mvc.put().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
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

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String updateHoldingRequest(String quantity, String avgPrice, int version) {
        return """
                {"quantity":"%s","avgPrice":"%s","version":%d}
                """.formatted(quantity, avgPrice, version);
    }
}
