package com.allfolio;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 014 — application.yml의 percentiles-histogram 설정
 * (allfolio.simulation.duration)이 실제로 프로메테우스 히스토그램 버킷을 만들어내는지 검증한다.
 * Task 015(시뮬레이터)가 아직 없어 실제 Timer.record() 호출 코드가 없으므로,
 * MeterRegistry로 직접 같은 이름의 샘플을 기록해 설정이 살아있음을 대체 검증한다.
 */
@AutoConfigureMockMvc
class MetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void simulationDurationTimerExposesHistogramBuckets() throws UnsupportedEncodingException {
        meterRegistry.timer("allfolio.simulation.duration").record(Duration.ofMillis(1));

        MvcTestResult result = mvc.get().uri("/actuator/prometheus").exchange();

        assertThat(result).hasStatusOk();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("allfolio_simulation_duration_seconds_bucket{le=\"");
    }
}
