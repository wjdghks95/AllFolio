package com.allfolio.domain.repository;

import com.allfolio.domain.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * GET /v1/assets/{id}/transactions 첫 페이지(cursor 없음). (tradedAt DESC, id DESC) 복합
     * 정렬 — idx_transactions_asset_traded 인덱스를 그대로 탄다(db/migration/V2__indexes.sql).
     */
    List<Transaction> findByAsset_IdOrderByTradedAtDescIdDesc(UUID assetId, Pageable pageable);

    /**
     * GET /v1/assets/{id}/transactions 다음 페이지. cursor는 이전 페이지 마지막 항목의
     * (tradedAt, id)다(불투명 문자열 계약, TransactionService에서 인코딩/디코딩).
     */
    @Query("SELECT t FROM Transaction t WHERE t.asset.id = :assetId "
            + "AND (t.tradedAt < :tradedAt OR (t.tradedAt = :tradedAt AND t.id < :id)) "
            + "ORDER BY t.tradedAt DESC, t.id DESC")
    List<Transaction> findByAsset_IdBeforeCursor(@Param("assetId") UUID assetId,
            @Param("tradedAt") Instant tradedAt, @Param("id") UUID id, Pageable pageable);
}
