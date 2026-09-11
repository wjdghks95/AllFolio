package com.allfolio.infra.sse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.CandleInterval;
import com.allfolio.domain.repository.UserRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * docs/ROADMAP.md Task 028 「SSE 스트리밍 백엔드(COIN 전용)」 — 실제 서버(RANDOM_PORT)를 기동해
 * {@code java.net.http.HttpClient}로 진짜 {@code text/event-stream} 연결을 열고 소비한다.
 * {@link CandlePushScheduler}·{@link CandleSseRegistry}와 같은 패키지에 둬서 {@code @Scheduled}
 * 메서드(패키지 접근 제한자)를 직접 호출할 수 있게 한다.
 *
 * <p>{@code MockMvcTester}(비동기 디스패치 시뮬레이션) 대신 이 방식을 택한 이유: {@link
 * org.springframework.web.servlet.mvc.method.annotation.SseEmitter}의 완료·에러 콜백은 실제
 * DispatcherServlet 비동기 요청 처리 중에 {@code initialize(Handler)}로 연결된 뒤에야 동작한다
 * (바이트코드 직접 확인, {@link CandleSseRegistryTest} Javadoc 참고) — MockMvc의 비동기 디스패치
 * 시뮬레이션이 이 실제 연결/해제 lifecycle을 얼마나 충실히 재현하는지 불확실한 반면, 진짜 HTTP
 * 연결을 열고 닫으면 "연결 종료 시 자동 정리" 같은 요구사항을 있는 그대로 검증할 수 있다.
 *
 * <p>폴링 스케줄러({@code fixedDelayString="${allfolio.price-cache.coin-fresh-ttl}"}, 기본 10초)를
 * 실제 타이머로 기다리지 않고 {@link CandlePushScheduler#pollAndPush()}/{@code sendHeartbeat()}를
 * 패키지 접근으로 직접 호출한다 — 실제 스케줄 주기를 기다리는 것보다 빠르고 결정적이다(테스트가
 * {@code @Scheduled} 애너테이션 자체의 동작이 아니라 push 로직의 정확성을 검증하는 게 목적이므로).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CandleSseStreamIntegrationTest extends AbstractIntegrationTest {

    private static WireMockServer upbitWireMock;

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandleSseRegistry registry;

    @Autowired
    private CandlePushScheduler pushScheduler;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger schedulerLogger;

    @BeforeAll
    static void startWireMock() {
        upbitWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upbitWireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        upbitWireMock.stop();
    }

    @DynamicPropertySource
    static void sseProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + upbitWireMock.port());
    }

    @BeforeEach
    void setUp() {
        upbitWireMock.resetAll();
        userRepository.deleteAll();

        schedulerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CandlePushScheduler.class);
        schedulerLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        schedulerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(logAppender);
    }

    @Test
    void subscribingViaQueryTokenReceivesCandleUpdateAfterPollAndDeduplicatesUnchangedTicks() throws Exception {
        String token = signupAndGetToken("sse-trader-a@example.com");
        String assetId = createCoinAsset(token, "KRW-SSE1");
        CandleSubscriptionKey key = new CandleSubscriptionKey("KRW-SSE1", "KRW", CandleInterval.DAY);

        stubDayCandle("KRW-SSE1", "100500000.0");

        HttpResponse<Stream<String>> response = openStream(assetId, token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).contains("text/event-stream"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.subscribersOf(key).size() == 1);

        BlockingQueue<String> lines = collectLines(response.body());

        pushScheduler.pollAndPush();
        String dataLine = awaitDataLine(lines);
        Map<String, Object> firstPayload = objectMapper.readValue(dataLine, Map.class);
        assertThat(firstPayload.get("close")).isEqualTo("100500000.00000000");

        // 같은 값으로 다시 폴링하면 변경분이 없으니 추가 이벤트가 없어야 한다(design decision #3 dedup).
        pushScheduler.pollAndPush();
        assertThat(lines.poll(1, TimeUnit.SECONDS)).isNull();

        // 값이 바뀌면 그제서야 새 이벤트가 온다.
        upbitWireMock.resetAll();
        stubDayCandle("KRW-SSE1", "101200000.0");
        pushScheduler.pollAndPush();
        String secondDataLine = awaitDataLine(lines);
        Map<String, Object> secondPayload = objectMapper.readValue(secondDataLine, Map.class);
        assertThat(secondPayload.get("close")).isEqualTo("101200000.00000000");

        // 전송 스레드 로그에 traceId가 채워졌는지 실측(MdcPropagation 적용 검증).
        await().atMost(5, TimeUnit.SECONDS).until(() -> logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("SSE 캔들 이벤트 전송 완료")));
        ILoggingEvent sendLogEvent = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("SSE 캔들 이벤트 전송 완료"))
                .findFirst().orElseThrow();
        assertThat(sendLogEvent.getMDCPropertyMap().get("traceId")).isNotBlank();

        response.body().close();
    }

    @Test
    void heartbeatIsDeliveredAsSseComment() throws Exception {
        String token = signupAndGetToken("sse-trader-heartbeat@example.com");
        String assetId = createCoinAsset(token, "KRW-SSE2");
        CandleSubscriptionKey key = new CandleSubscriptionKey("KRW-SSE2", "KRW", CandleInterval.DAY);

        HttpResponse<Stream<String>> response = openStream(assetId, token);
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.subscribersOf(key).size() == 1);
        BlockingQueue<String> lines = collectLines(response.body());

        pushScheduler.sendHeartbeat();

        String heartbeatLine = lines.poll(5, TimeUnit.SECONDS);
        assertThat(heartbeatLine).isNotNull().startsWith(":").contains("heartbeat");

        response.body().close();
    }

    @Test
    void disconnectingClientEventuallyClearsRegistrySubscription() throws Exception {
        String token = signupAndGetToken("sse-trader-disconnect@example.com");
        String assetId = createCoinAsset(token, "KRW-SSE3");
        CandleSubscriptionKey key = new CandleSubscriptionKey("KRW-SSE3", "KRW", CandleInterval.DAY);

        HttpResponse<Stream<String>> response = openStream(assetId, token);
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.subscribersOf(key).size() == 1);

        // 클라이언트가 연결을 끊는다 — 서버는 다음 전송 시도(heartbeat)에서야 이를 감지한다.
        response.body().close();

        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            pushScheduler.sendHeartbeat();
            return registry.subscribersOf(key).isEmpty();
        });
    }

    @Test
    void queryTokenAuthenticationFailsWithInvalidTokenReturnsUnauthorized() throws Exception {
        String assetId = createCoinAsset(signupAndGetToken("sse-trader-b@example.com"), "KRW-SSE4");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(streamUri(assetId) + "&token=not-a-valid-token"))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void missingTokenReturnsUnauthorized() throws Exception {
        String assetId = createCoinAsset(signupAndGetToken("sse-trader-c@example.com"), "KRW-SSE5");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(streamUri(assetId)))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void stockAssetIsRejectedWithValidationError() throws Exception {
        String token = signupAndGetToken("sse-trader-d@example.com");
        String assetId = createStockAsset(token, "005930");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(streamUri(assetId) + "&token=" + token))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        assertThat(body.get("code")).isEqualTo("VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------------
    // 헬퍼
    // ---------------------------------------------------------------------

    private HttpResponse<Stream<String>> openStream(String assetId, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(streamUri(assetId) + "&token=" + token))
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    private String streamUri(String assetId) {
        return "http://localhost:" + port + "/v1/assets/" + assetId + "/candles/stream?interval=DAY";
    }

    /** 스트림의 각 라인을 별도 스레드에서 큐로 옮겨, 테스트 스레드는 블로킹 없이 poll할 수 있게 한다. */
    private BlockingQueue<String> collectLines(Stream<String> lineStream) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try {
                Iterator<String> iterator = lineStream.iterator();
                while (iterator.hasNext()) {
                    queue.add(iterator.next());
                }
            } catch (RuntimeException ignored) {
                // 스트림이 닫히면 반복자가 예외를 던질 수 있다 — 테스트에서 의도적으로 닫는 케이스.
            }
        });
        return queue;
    }

    /** SSE 프레임(id/event/data 라인 + 빈 줄 구분)에서 data: 라인의 JSON 본문만 꺼낸다. */
    private String awaitDataLine(BlockingQueue<String> lines) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(5, TimeUnit.SECONDS);
            if (line == null) {
                break;
            }
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
        }
        throw new AssertionError("data: 라인을 받지 못했습니다.");
    }

    private void stubDayCandle(String market, String closePrice) {
        upbitWireMock.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .withQueryParam("market", equalTo(market))
                .withQueryParam("count", equalTo("1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"%s","candle_date_time_utc":"2026-09-10T00:00:00",
                                  "candle_date_time_kst":"2026-09-10T09:00:00","opening_price":100000000.0,
                                  "high_price":101000000.0,"low_price":99000000.0,"trade_price":%s,
                                  "timestamp":1,"candle_acc_trade_price":1,"candle_acc_trade_volume":1,
                                  "prev_closing_price":1,"change_price":1,"change_rate":0.1}]
                                """.formatted(market, closePrice))));
    }

    private String signupAndGetToken(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(email)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        return (String) body.get("accessToken");
    }

    private String createCoinAsset(String token, String ticker) throws Exception {
        return createAsset(token, """
                {"ticker":"%s","name":"SSE코인","assetType":"COIN","currency":"KRW","quantity":"1","avgPrice":"100000000"}
                """.formatted(ticker));
    }

    private String createStockAsset(String token, String ticker) throws Exception {
        return createAsset(token, """
                {"ticker":"%s","name":"SSE주식","assetType":"STOCK","currency":"KRW","quantity":"10","avgPrice":"70000"}
                """.formatted(ticker));
    }

    private String createAsset(String token, String requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/assets"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        return (String) body.get("id");
    }
}
