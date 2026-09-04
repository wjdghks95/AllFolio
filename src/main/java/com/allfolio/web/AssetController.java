package com.allfolio.web;

import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.service.AssetService;
import com.allfolio.domain.service.PriceService;
import com.allfolio.web.dto.AssetListResponse;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.CreateAssetRequest;
import com.allfolio.web.dto.PriceResponse;
import com.allfolio.web.dto.UpdateHoldingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 클래스 레벨 {@code @Validated}를 붙이지 않는다 — Spring 7의 내장 메서드 파라미터 검증은
 * 클래스에 {@code @Validated}가 없을 때만 동작하고, 붙이는 순간 AOP 기반 구식 경로로 전환돼
 * {@code jakarta.validation.ConstraintViolationException}을 던진다. 그 예외는
 * GlobalExceptionHandler에 매핑돼 있지 않아 500으로 새어 나간다(docs/ROADMAP.md Task 012
 * code-reviewer 지적). {@code @Min}/{@code @Max}가 붙은 {@code limit} 파라미터 검증은
 * {@code @Validated} 없이도 내장 경로(HandlerMethodValidationException → 400
 * VALIDATION_ERROR)로 이미 동작한다.
 */
@RestController
@RequestMapping("/v1/assets")
public class AssetController {

    private final AssetService assetService;
    private final PriceService priceService;

    public AssetController(AssetService assetService, PriceService priceService) {
        this.assetService = assetService;
        this.priceService = priceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse create(@Valid @RequestBody CreateAssetRequest request, Authentication authentication) {
        return assetService.createAsset(userId(authentication), request);
    }

    @GetMapping
    public AssetListResponse list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) UUID cursor,
            Authentication authentication) {
        return assetService.listAssets(userId(authentication), cursor, limit);
    }

    @GetMapping("/{id}")
    public AssetResponse get(@PathVariable UUID id, Authentication authentication) {
        return assetService.getAsset(userId(authentication), id);
    }

    /** stale(캐시 폴백) 응답은 에러가 아닌 성공 응답이므로 206 Partial Content로 구분한다. */
    @GetMapping("/{id}/price")
    public ResponseEntity<PriceResponse> getPrice(@PathVariable UUID id, Authentication authentication) {
        PricedQuote quote = priceService.getPrice(userId(authentication), id);
        PriceResponse body = new PriceResponse(quote.price().amount().toPlainString(), quote.price().currency(),
                quote.price().asOf(), quote.stale());
        HttpStatus status = quote.stale() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @PutMapping("/{id}/holdings")
    public AssetResponse updateHoldings(@PathVariable UUID id, @Valid @RequestBody UpdateHoldingRequest request,
            Authentication authentication) {
        return assetService.updateHolding(userId(authentication), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        assetService.deleteAsset(userId(authentication), id);
    }

    /** JwtFilter가 principal에 userId.toString()을 심어둔다(infra/security/JwtFilter.java). */
    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
