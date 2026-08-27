package com.allfolio;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.domain.service.SimulationService;
import com.allfolio.web.dto.SimulateAvgPriceRequest;
import com.allfolio.web.dto.SimulateAvgPriceResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SimulationService.simulate()의 가중평균 계산 로직만 검증하는 순수 단위 테스트
 * (docs/ROADMAP.md Task 016). HTTP 상태 코드·JSON 직렬화 계약은 SimulateIntegrationTest 소관 —
 * 이 클래스는 Spring 컨텍스트 없이 계산 정확성만 다룬다(책임 분리).
 */
@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private HoldingRepository holdingRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SimulationService simulationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        simulationService = new SimulationService(assetRepository, holdingRepository, meterRegistry);
    }

    @Test
    void goldenCaseComputesWeightedAverageAndRecordsMetric() {
        givenHolding(AssetType.STOCK, "KRW", "10", "60000");

        SimulateAvgPriceResponse response = simulate("55000", "5");

        assertThat(response.currentAvgPrice()).isEqualTo("60000");
        assertThat(response.expectedAvgPrice()).isEqualTo("58333");
        assertThat(response.expectedQuantity()).isEqualTo("15.00000000");
        assertThat(meterRegistry.timer("allfolio.simulation.duration").count()).isEqualTo(1);
    }

    @Test
    void halfUpRoundsKrwScaleBoundaryUp() {
        givenHolding(AssetType.STOCK, "KRW", "1", "1");

        SimulateAvgPriceResponse response = simulate("2", "1");

        // (1*1 + 2*1) / 2 = 1.5 -> KRW scale 0, HALF_UP -> 2
        assertThat(response.expectedAvgPrice()).isEqualTo("2");
    }

    @Test
    void cashAssetKeepsAvgPriceAtOne() {
        givenHolding(AssetType.CASH, "KRW", "100000", "1");

        SimulateAvgPriceResponse response = simulate("1", "50000");

        assertThat(response.currentAvgPrice()).isEqualTo("1");
        assertThat(response.expectedAvgPrice()).isEqualTo("1");
    }

    @Test
    void coinRoundsHalfUpAtEighthDecimal() {
        givenHolding(AssetType.COIN, "KRW", "1", "1");

        SimulateAvgPriceResponse response = simulate("2", "2");

        // (1*1 + 2*2) / 3 = 1.66666666... -> 코인 scale 8, HALF_UP -> 1.66666667
        assertThat(response.expectedAvgPrice()).isEqualTo("1.66666667");
    }

    /**
     * currentAvgPrice는 DB 왕복 scale이 아닌 응답 scale로 재정규화돼야 한다(Task 012/013 전례).
     * avgPrice를 이미 목표 scale(정수)로 넣는 다른 케이스들은 SimulationService.simulate()의
     * currentAvgPrice.setScale(...) 호출을 지워도 우연히 통과한다 — 그 뮤테이션을 실제로 잡으려면
     * 목표 scale보다 자리수가 많은 입력이 필요하다(code-reviewer 지적).
     */
    @Test
    void currentAvgPriceIsReformattedToResponseScaleNotRawDbScale() {
        givenHolding(AssetType.STOCK, "KRW", "10", "60000.4");

        SimulateAvgPriceResponse response = simulate("55000", "5");

        assertThat(response.currentAvgPrice()).isEqualTo("60000");
    }

    @Test
    void assetNotOwnedByUserThrowsAssetNotFoundAndSkipsMetric() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> simulate("55000", "5")).isInstanceOf(AssetNotFoundException.class);
        // 실패 경로는 미계측이 의도된 설계다(docs/ROADMAP.md Task 015) — Timer.Sample은 stop() 전엔
        // 레지스트리에 아무것도 등록하지 않으므로, 타이머 자체가 존재하지 않아야 한다.
        assertThat(meterRegistry.find("allfolio.simulation.duration").timer()).isNull();
    }

    private void givenHolding(AssetType assetType, String currency, String quantity, String avgPrice) {
        User user = User.of("trader@example.com", "hash");
        Asset asset = Asset.of(user, "TICKER", "종목", assetType, currency);
        Holding holding = Holding.of(asset, new BigDecimal(quantity), new BigDecimal(avgPrice));

        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.of(asset));
        when(holdingRepository.findByAsset_Id(any())).thenReturn(Optional.of(holding));
    }

    private SimulateAvgPriceResponse simulate(String additionalPrice, String additionalQuantity) {
        SimulateAvgPriceRequest request = new SimulateAvgPriceRequest(
                assetId, new BigDecimal(additionalPrice), new BigDecimal(additionalQuantity));
        return simulationService.simulate(userId, request);
    }
}
