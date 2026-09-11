package com.allfolio.web;

import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.service.AssetService;
import com.allfolio.domain.service.CandleService;
import com.allfolio.domain.service.PriceService;
import com.allfolio.domain.service.TransactionService;
import com.allfolio.infra.sse.CandleSseRegistry;
import com.allfolio.infra.sse.CandleSubscriptionKey;
import com.allfolio.web.dto.AssetListResponse;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.CandleSeriesResponse;
import com.allfolio.web.dto.CreateAssetRequest;
import com.allfolio.web.dto.CreateTransactionRequest;
import com.allfolio.web.dto.PriceResponse;
import com.allfolio.web.dto.TransactionListResponse;
import com.allfolio.web.dto.TransactionResponse;
import com.allfolio.web.dto.UpdateHoldingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final TransactionService transactionService;
    private final CandleService candleService;
    private final CandleSseRegistry candleSseRegistry;

    public AssetController(AssetService assetService, PriceService priceService,
            TransactionService transactionService, CandleService candleService,
            CandleSseRegistry candleSseRegistry) {
        this.assetService = assetService;
        this.priceService = priceService;
        this.transactionService = transactionService;
        this.candleService = candleService;
        this.candleSseRegistry = candleSseRegistry;
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

    /**
     * interval/before는 Spring의 자동 enum/LocalDate 바인딩을 쓰지 않고 String으로 받는다 —
     * 자동 바인딩 실패는 GlobalExceptionHandler 매핑 밖(MethodArgumentTypeMismatchException)으로
     * 새 500이 된다(web/CLAUDE.md, docs/ROADMAP.md Task 028). CandleService가 직접 파싱해
     * InvalidCandleQueryException(400 VALIDATION_ERROR)으로 변환한다.
     */
    @GetMapping("/{id}/candles")
    public CandleSeriesResponse getCandles(@PathVariable UUID id,
            @RequestParam String interval,
            @RequestParam(required = false) String before,
            Authentication authentication) {
        return candleService.getCandles(userId(authentication), id, interval, before);
    }

    /**
     * COIN 전용 실시간 캔들 스트리밍(Task 028). 소유권 검증(404)과 COIN 전용 검증(400)은
     * {@link CandleService#resolveCoinSubscriptionKey}가 {@code getCandles}와 동일한 패턴으로
     * 수행한다.
     *
     * <p>{@link SseEmitter} 타임아웃을 기본값(30초)으로 두지 않고 {@code Long.MAX_VALUE}(사실상
     * 무제한)로 연다 — heartbeat(30초 간격)와 무관하게 Spring의 기본 비동기 타임아웃으로 연결이
     * 끊기는 흔한 함정을 피하기 위함이다. 연결 종료는 타임아웃이 아니라 클라이언트 disconnect
     * 감지(전송 실패)로만 일어나며, 그 정리는 {@link CandleSseRegistry#subscribe}가 등록한
     * 콜백이 담당한다.
     */
    @GetMapping(value = "/{id}/candles/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCandles(@PathVariable UUID id,
            @RequestParam String interval,
            Authentication authentication) {
        CandleSubscriptionKey key = candleService.resolveCoinSubscriptionKey(userId(authentication), id, interval);
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        candleSseRegistry.subscribe(key, emitter);
        return emitter;
    }

    @GetMapping("/{id}/transactions")
    public TransactionListResponse listTransactions(@PathVariable UUID id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            Authentication authentication) {
        return transactionService.listTransactions(userId(authentication), id, cursor, limit);
    }

    @PostMapping("/{id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@PathVariable UUID id,
            @Valid @RequestBody CreateTransactionRequest request, Authentication authentication) {
        return transactionService.createTransaction(userId(authentication), id, request);
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
