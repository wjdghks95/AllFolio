package com.allfolio.domain.repository;

import com.allfolio.domain.Asset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByIdAndUser_Id(UUID id, UUID userId);

    /** GET /v1/portfolio(docs/ROADMAP.md Task 013)용 전체 조회. GET /v1/assets와 동일하게 id DESC(최신 등록순)로 응답 순서를 고정한다. */
    List<Asset> findByUser_IdOrderByIdDesc(UUID userId);

    /** GET /v1/assets 첫 페이지(cursor 없음). id DESC만으로 최신 등록순이 보장된다 — UUID v7이 시간순 단조 증가이기 때문(docs/ROADMAP.md Task 012). */
    List<Asset> findByUser_IdOrderByIdDesc(UUID userId, Pageable pageable);

    /** GET /v1/assets 다음 페이지. cursor는 이전 페이지 마지막 항목의 id다(불투명 문자열 계약). */
    List<Asset> findByUser_IdAndIdLessThanOrderByIdDesc(UUID userId, UUID cursor, Pageable pageable);
}
