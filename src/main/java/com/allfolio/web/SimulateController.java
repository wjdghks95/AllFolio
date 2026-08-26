package com.allfolio.web;

import com.allfolio.domain.service.SimulationService;
import com.allfolio.web.dto.SimulateAvgPriceRequest;
import com.allfolio.web.dto.SimulateAvgPriceResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 클래스 레벨 {@code @Validated}를 붙이지 않는다 — AssetController와 같은 이유(Spring 7의
 * 내장 메서드 파라미터 검증과 충돌해 500이 새는 결함 전례, docs/ROADMAP.md Task 012).
 */
@RestController
@RequestMapping("/v1/simulate/avg-price")
public class SimulateController {

    private final SimulationService simulationService;

    public SimulateController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping
    public SimulateAvgPriceResponse simulate(@Valid @RequestBody SimulateAvgPriceRequest request,
            Authentication authentication) {
        return simulationService.simulate(userId(authentication), request);
    }

    /** JwtFilter가 principal에 userId.toString()을 심어둔다(infra/security/JwtFilter.java). */
    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
