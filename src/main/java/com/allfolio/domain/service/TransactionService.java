package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.Transaction;
import com.allfolio.domain.TransactionType;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.AvgPriceRequiredException;
import com.allfolio.domain.exception.InsufficientHoldingQuantityException;
import com.allfolio.domain.exception.InvalidCursorException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.domain.repository.TransactionRepository;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.CreateTransactionRequest;
import com.allfolio.web.dto.TransactionItemResponse;
import com.allfolio.web.dto.TransactionListResponse;
import com.allfolio.web.dto.TransactionResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 거래 이력 조회·입력 (docs/ROADMAP.md Task 024). AssetService·SimulationService와 동일한 관례를 따라
 * web.dto 요청/응답 타입을 직접 주고받는다. 소유권 검증은 AssetService와 동일하게 404
 * ASSET_NOT_FOUND로 통일한다(403이면 "그 ID가 존재한다"는 사실이 새어 나간다).
 */
@Service
public class TransactionService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AssetRepository assetRepository, HoldingRepository holdingRepository,
            TransactionRepository transactionRepository) {
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 커서 페이지네이션. limit+1건을 읽어 다음 페이지 존재 여부를 판단한다(AssetService.listAssets와
     * 동일 패턴). nextCursor는 "<tradedAt epochSecond>.<nano>_<uuid>" 형태의 불투명 문자열이다.
     * epochMillis만 담으면 transactions.traded_at(TIMESTAMPTZ, 마이크로초 정밀도)이 밀리초 이하로
     * 절삭돼, 같은 밀리초에 여러 거래가 있을 때 타이브레이크 조건(tradedAt 정확 일치)에서 일부가
     * 순회에서 누락된다(code-reviewer 실측). epochSecond+nano로 나노초까지 보존한다.
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(UUID userId, UUID assetId, String cursor, int limit) {
        findOwnedAsset(userId, assetId);

        Pageable page = PageRequest.of(0, limit + 1);
        List<Transaction> rows = cursor == null
                ? transactionRepository.findByAsset_IdOrderByTradedAtDescIdDesc(assetId, page)
                : findByCursor(assetId, cursor, page);

        boolean hasNext = rows.size() > limit;
        List<Transaction> pageRows = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext ? encodeCursor(pageRows.getLast()) : null;

        List<TransactionItemResponse> items = pageRows.stream().map(this::toItemResponse).toList();
        return new TransactionListResponse(items, nextCursor);
    }

    /**
     * Transaction 저장과 Holding 갱신을 한 트랜잭션에서 처리한다. BUY는 가중평균 재계산,
     * SELL은 수량 차감(초과 매도 시 예외, 평단가 불변), DIVIDEND는 quantity/avgPrice 완전 불변.
     * CASH 자산은 계산된 avgPrice를 무시하고 항상 1을 유지한다(Task 006 결정 #1과 동일 규칙).
     *
     * <p>낙관적 잠금은 별도로 비교하지 않는다 — 이 요청에 version 필드가 없으므로, Hibernate가
     * flush 시점에 자동으로 DB version과 비교해 충돌하면 ObjectOptimisticLockingFailureException을
     * 던지도록 그대로 둔다(AssetService.updateHolding과 달리 클라이언트가 과거에 읽은 version을
     * 실어 보내지 않는 설계).
     */
    @Transactional
    public TransactionResponse createTransaction(UUID userId, UUID assetId, CreateTransactionRequest request) {
        Asset asset = findOwnedAsset(userId, assetId);
        Holding holding = findHolding(assetId);

        TransactionType txType = request.txType();
        BigDecimal quantity = request.quantity();
        BigDecimal price = effectivePrice(asset, request.price());

        BigDecimal currentQuantity = holding.getQuantity();
        BigDecimal currentAvgPrice = holding.getAvgPrice();

        HoldingUpdate update = switch (txType) {
            case BUY -> {
                BigDecimal newQuantity = currentQuantity.add(quantity);
                int scale = PrecisionScale.scaleFor(asset.getAssetType(), asset.getCurrency());
                BigDecimal totalCost = currentQuantity.multiply(currentAvgPrice).add(quantity.multiply(price));
                yield new HoldingUpdate(newQuantity, totalCost.divide(newQuantity, scale, RoundingMode.HALF_UP));
            }
            case SELL -> {
                if (quantity.compareTo(currentQuantity) > 0) {
                    throw new InsufficientHoldingQuantityException("보유 수량보다 많은 수량을 매도할 수 없습니다.");
                }
                yield new HoldingUpdate(currentQuantity.subtract(quantity), currentAvgPrice);
            }
            case DIVIDEND -> new HoldingUpdate(currentQuantity, currentAvgPrice);
        };

        // DIVIDEND는 quantity/avgPrice가 완전 불변이어야 한다(Javadoc 계약). holding.update()는 값이
        // 같아도 updatedAt을 항상 갱신하고 dirty checking으로 version까지 증가시키므로(code-reviewer
        // 실측), BUY/SELL에서만 호출한다.
        if (txType != TransactionType.DIVIDEND) {
            BigDecimal effectiveAvgPrice = asset.getAssetType() == AssetType.CASH ? BigDecimal.ONE : update.avgPrice();
            holding.update(update.quantity(), effectiveAvgPrice);
            holdingRepository.flush();
        }

        Transaction transaction = transactionRepository.save(Transaction.of(asset, txType, price, quantity, request.tradedAt()));
        return new TransactionResponse(transaction.getId(), transaction.getTxType(),
                transaction.getPrice().toPlainString(), transaction.getQuantity().toPlainString(),
                transaction.getTradedAt(), toAssetResponse(asset, holding));
    }

    /**
     * CASH 자산은 요청 price와 무관하게 1을 쓴다(holding.avgPrice와 동일 규칙). 그 외 타입에서
     * price가 null이면 UpdateHoldingRequest와 동일하게 400 VALIDATION_ERROR로 응답한다.
     */
    private BigDecimal effectivePrice(Asset asset, BigDecimal requestPrice) {
        if (asset.getAssetType() == AssetType.CASH) {
            return BigDecimal.ONE;
        }
        if (requestPrice == null) {
            throw new AvgPriceRequiredException("현금이 아닌 자산은 거래 가격을 입력해야 합니다.");
        }
        return requestPrice;
    }

    private List<Transaction> findByCursor(UUID assetId, String cursor, Pageable page) {
        CursorPosition position = decodeCursor(cursor);
        return transactionRepository.findByAsset_IdBeforeCursor(assetId, position.tradedAt(), position.id(), page);
    }

    /**
     * 파싱 실패(구분자 없음, 숫자/UUID 형식 아님 등)는 전부 RuntimeException 계열이라 이 catch로
     * 수렴한다 — 그대로 던지면 GlobalExceptionHandler의 Exception 폴백에 걸려 500으로 새므로
     * InvalidCursorException(400 VALIDATION_ERROR)으로 변환한다(code-reviewer 실측).
     */
    private CursorPosition decodeCursor(String cursor) {
        try {
            int separatorIndex = cursor.indexOf('_');
            String tradedAtPart = cursor.substring(0, separatorIndex);
            UUID id = UUID.fromString(cursor.substring(separatorIndex + 1));
            int dotIndex = tradedAtPart.indexOf('.');
            long epochSecond = Long.parseLong(tradedAtPart.substring(0, dotIndex));
            int nano = Integer.parseInt(tradedAtPart.substring(dotIndex + 1));
            return new CursorPosition(Instant.ofEpochSecond(epochSecond, nano), id);
        } catch (RuntimeException e) {
            throw new InvalidCursorException("잘못된 cursor 값입니다.");
        }
    }

    private String encodeCursor(Transaction transaction) {
        Instant tradedAt = transaction.getTradedAt();
        return tradedAt.getEpochSecond() + "." + tradedAt.getNano() + "_" + transaction.getId();
    }

    private Asset findOwnedAsset(UUID userId, UUID assetId) {
        return assetRepository.findByIdAndUser_Id(assetId, userId)
                .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));
    }

    /** 자산 생성 시 Holding이 항상 함께 만들어지므로(불변식) 비어있는 경우는 데이터 정합성 오류다. */
    private Holding findHolding(UUID assetId) {
        return holdingRepository.findByAsset_Id(assetId)
                .orElseThrow(() -> new IllegalStateException("자산 " + assetId + "에 대한 보유 정보가 없습니다."));
    }

    private TransactionItemResponse toItemResponse(Transaction transaction) {
        return new TransactionItemResponse(transaction.getId(), transaction.getTxType(),
                transaction.getPrice().toPlainString(), transaction.getQuantity().toPlainString(),
                transaction.getTradedAt());
    }

    /** AssetService.toResponse와 동일한 필드 매핑(엔티티→DTO 변환은 서비스 트랜잭션 안에서 수행). */
    private AssetResponse toAssetResponse(Asset asset, Holding holding) {
        return new AssetResponse(
                asset.getId(),
                asset.getTicker(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCurrency(),
                holding.getQuantity().toPlainString(),
                holding.getAvgPrice().toPlainString(),
                holding.getVersion(),
                holding.getUpdatedAt());
    }

    private record CursorPosition(Instant tradedAt, UUID id) {
    }

    private record HoldingUpdate(BigDecimal quantity, BigDecimal avgPrice) {
    }
}
