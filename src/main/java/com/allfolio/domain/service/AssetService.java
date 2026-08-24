package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.Transaction;
import com.allfolio.domain.TransactionType;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.AvgPriceRequiredException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.domain.repository.TransactionRepository;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.web.dto.AssetListResponse;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.CreateAssetRequest;
import com.allfolio.web.dto.UpdateHoldingRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 자산 CRUD (docs/ROADMAP.md Task 012). ASSET_NOT_FOUND는 자산이 없을 때와 남의 자산일 때
 * 모두 던진다 — 403이면 "그 ID는 존재한다"는 사실이 새어 나간다.
 */
@Service
public class AssetService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public AssetService(AssetRepository assetRepository, HoldingRepository holdingRepository,
            TransactionRepository transactionRepository, UserRepository userRepository) {
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Asset+Holding+Transaction(BUY)을 한 트랜잭션에서 함께 생성한다 (docs/ROADMAP.md Task 006 결정 #2).
     * CASH는 요청의 avgPrice와 무관하게 항상 1을 쓴다 (Task 006 결정 #1).
     */
    @Transactional
    public AssetResponse createAsset(UUID userId, CreateAssetRequest request) {
        User userRef = userRepository.getReferenceById(userId);
        BigDecimal effectiveAvgPrice = request.assetType() == AssetType.CASH ? BigDecimal.ONE : request.avgPrice();

        Asset asset = assetRepository.save(
                Asset.of(userRef, request.ticker(), request.name(), request.assetType(), request.currency()));
        Holding holding = holdingRepository.save(Holding.of(asset, request.quantity(), effectiveAvgPrice));
        transactionRepository.save(
                Transaction.of(asset, TransactionType.BUY, effectiveAvgPrice, request.quantity(), Instant.now()));

        return toResponse(asset, holding);
    }

    /**
     * 커서 페이지네이션. limit+1건을 읽어 다음 페이지 존재 여부를 판단한다(별도 COUNT 쿼리 불필요).
     * id DESC만으로 최신 등록순이 보장된다 — UUID v7이 시간순 단조 증가이기 때문(T1에서 확인).
     */
    @Transactional(readOnly = true)
    public AssetListResponse listAssets(UUID userId, UUID cursor, int limit) {
        Pageable page = PageRequest.of(0, limit + 1);
        List<Asset> rows = cursor == null
                ? assetRepository.findByUser_IdOrderByIdDesc(userId, page)
                : assetRepository.findByUser_IdAndIdLessThanOrderByIdDesc(userId, cursor, page);

        boolean hasNext = rows.size() > limit;
        List<Asset> pageRows = hasNext ? rows.subList(0, limit) : rows;

        List<UUID> assetIds = pageRows.stream().map(Asset::getId).toList();
        Map<UUID, Holding> holdingsByAssetId = holdingRepository.findByAsset_IdIn(assetIds).stream()
                .collect(Collectors.toMap(h -> h.getAsset().getId(), Function.identity()));

        List<AssetResponse> items = pageRows.stream()
                .map(asset -> toResponse(asset, holdingsByAssetId.get(asset.getId())))
                .toList();
        String nextCursor = hasNext ? pageRows.getLast().getId().toString() : null;

        return new AssetListResponse(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public AssetResponse getAsset(UUID userId, UUID assetId) {
        Asset asset = findOwnedAsset(userId, assetId);
        Holding holding = findHolding(assetId);
        return toResponse(asset, holding);
    }

    /**
     * 낙관적 잠금(docs/ROADMAP.md Task 012): 이 트랜잭션에서 방금 읽은 holding.getVersion()은
     * 항상 최신 값이라, Hibernate의 자동 @Version 검사만으론 "클라이언트가 과거에 읽은 값" 기준
     * 충돌을 잡지 못한다. 그래서 요청의 version과 명시적으로 비교해 다르면 직접 예외를 던진다.
     */
    @Transactional
    public AssetResponse updateHolding(UUID userId, UUID assetId, UpdateHoldingRequest request) {
        Asset asset = findOwnedAsset(userId, assetId);
        Holding holding = findHolding(assetId);

        if (holding.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Holding.class, holding.getId());
        }

        BigDecimal effectiveAvgPrice;
        if (asset.getAssetType() == AssetType.CASH) {
            effectiveAvgPrice = BigDecimal.ONE;
        } else if (request.avgPrice() == null) {
            throw new AvgPriceRequiredException("현금이 아닌 자산은 평단가를 입력해야 합니다.");
        } else {
            effectiveAvgPrice = request.avgPrice();
        }

        holding.update(request.quantity(), effectiveAvgPrice);
        // @Version은 실제 UPDATE가 나갈 때 증가한다. 트랜잭션 커밋(메서드 반환 이후)까지 기다리면
        // 응답에 담길 holding.getVersion()이 증가 전 값을 가리켜, 클라이언트가 이 응답으로 다음
        // 수정을 시도하면 매번 가짜 409가 난다 — 응답을 만들기 전에 명시적으로 flush한다.
        holdingRepository.flush();
        return toResponse(asset, holding);
    }

    /** assets 단건 삭제만으로 충분하다 — FK ON DELETE CASCADE가 holdings·transactions를 정리한다. */
    @Transactional
    public void deleteAsset(UUID userId, UUID assetId) {
        Asset asset = findOwnedAsset(userId, assetId);
        assetRepository.delete(asset);
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

    private AssetResponse toResponse(Asset asset, Holding holding) {
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
}
