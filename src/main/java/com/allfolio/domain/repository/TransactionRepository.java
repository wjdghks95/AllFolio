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
     * 정렬 — idx_transactions_asset_traded_id 인덱스를 그대로 탄다(db/migration/V5__transactions_cursor_index.sql).
     */
    List<Transaction> findByAsset_IdOrderByTradedAtDescIdDesc(UUID assetId, Pageable pageable);

    /**
     * GET /v1/assets/{id}/transactions 다음 페이지. cursor는 이전 페이지 마지막 항목의
     * (tradedAt, id)다(불투명 문자열 계약, TransactionService에서 인코딩/디코딩).
     *
     * <p>타이브레이크를 {@code OR}로 풀어 쓰지 말 것 — 논리적으로는 같지만 PostgreSQL이 OR 형태는
     * 인덱스 레인지로 내리지 못해 Filter로 처리한다(커서가 뒤로 갈수록 O(N), 2만 건 실측에서
     * Rows Removed by Filter 19,001). 행 값(row-value) 비교로 쓰면 idx_transactions_asset_traded_id
     * 위에서 통째로 Index Cond가 된다(Filter 0건, buffers 566→4).
     */
    @Query("SELECT t FROM Transaction t WHERE t.asset.id = :assetId "
            + "AND (t.tradedAt, t.id) < (:tradedAt, :id) "
            + "ORDER BY t.tradedAt DESC, t.id DESC")
    List<Transaction> findByAsset_IdBeforeCursor(@Param("assetId") UUID assetId,
            @Param("tradedAt") Instant tradedAt, @Param("id") UUID id, Pageable pageable);
}
