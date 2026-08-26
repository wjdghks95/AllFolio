package com.allfolio;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.User;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.domain.service.AssetService;
import com.allfolio.domain.service.SimulationService;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.CreateAssetRequest;
import com.allfolio.web.dto.SimulateAvgPriceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 015 「P99 응답시간 성능 검증」 — 물타기 시뮬레이터(F006)가
 * 「성능 KPI」의 P99 ≤ 5ms(1,000회 반복, holding 단건 조회 포함, JVM 워밍업 후)를 만족하는지 실측한다.
 *
 * <p>MockMvc/HTTP 스택을 거치지 않고 {@link SimulationService#simulate}를 직접 호출한다 — KPI가
 * "holding 단건 조회를 포함한 수치"로 정의돼 있을 뿐 HTTP 프레이밍·JSON 직렬화·Bean Validation
 * 오버헤드까지 포함한다고 보기 어렵기 때문이다(CLAUDE.md 「물타기 시뮬레이터(F006) 성능 KPI」).
 */
class SimulationPerformanceTest extends AbstractIntegrationTest {

    private static final int WARMUP_ITERATIONS = 200;
    private static final int MEASURED_ITERATIONS = 1000;
    private static final long P99_LIMIT_MILLIS = 5;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;
    private UUID assetId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = userRepository.save(User.of("perf-tester@example.com", "irrelevant-hash"));
        userId = user.getId();

        CreateAssetRequest createRequest = new CreateAssetRequest(
                "005930", "삼성전자", AssetType.STOCK, "KRW",
                new BigDecimal("10"), new BigDecimal("60000"));
        AssetResponse asset = assetService.createAsset(userId, createRequest);
        assetId = asset.id();
    }

    /**
     * 워밍업 200회로 JIT 컴파일을 유도한 뒤, 1,000회를 반복 호출하며 각 호출의 소요시간(나노초)을
     * 기록한다. 정렬 후 0-based 인덱스 990(991번째 값)을 P99로 삼는다 — nearest-rank 정의상 정확한
     * P99는 인덱스 989(990번째 값)이지만, 한 칸 더 보수적인 값을 택해 거짓 통과(false pass)를 피한다.
     */
    @Test
    void simulateP99ResponseTimeIsAtMostFiveMilliseconds() {
        SimulateAvgPriceRequest request =
                new SimulateAvgPriceRequest(assetId, new BigDecimal("55000"), new BigDecimal("5"));

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simulationService.simulate(userId, request);
        }

        long[] durationsNanos = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            simulationService.simulate(userId, request);
            durationsNanos[i] = System.nanoTime() - start;
        }

        Arrays.sort(durationsNanos);
        long p99Nanos = durationsNanos[990];
        double p99Millis = p99Nanos / 1_000_000.0;

        System.out.printf(
                "[SimulationPerformanceTest] P99 = %.4f ms (warmup=%d, measured=%d, limit=%d ms)%n",
                p99Millis, WARMUP_ITERATIONS, MEASURED_ITERATIONS, P99_LIMIT_MILLIS);

        assertThat(p99Millis).isLessThanOrEqualTo(P99_LIMIT_MILLIS);
    }
}
