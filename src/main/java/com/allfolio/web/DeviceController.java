package com.allfolio.web;

import com.allfolio.domain.service.DeviceTokenService;
import com.allfolio.web.dto.DeviceResponse;
import com.allfolio.web.dto.RegisterDeviceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 기기 토큰(FCM 등) 등록/해제 (docs/ROADMAP.md Task 029). 클래스 레벨 {@code @Validated}를
 * 붙이지 않는다 — web/CLAUDE.md 컨벤션(AssetController와 동일한 이유).
 */
@RestController
@RequestMapping("/v1/devices")
public class DeviceController {

    private final DeviceTokenService deviceTokenService;

    public DeviceController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@Valid @RequestBody RegisterDeviceRequest request, Authentication authentication) {
        return deviceTokenService.register(userId(authentication), request.token(), request.platform());
    }

    /**
     * 경로에 토큰 원문이 아닌 device_tokens 자체 UUID(id)를 쓴다 — 민감한 토큰 값이 URL/로그에
     * 남는 걸 피하기 위함(SSE {@code ?token=} 노출 전례 참고).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, Authentication authentication) {
        deviceTokenService.revoke(userId(authentication), id);
    }

    /** JwtFilter가 principal에 userId.toString()을 심어둔다(infra/security/JwtFilter.java). */
    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
