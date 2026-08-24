package com.allfolio.domain.repository;

import com.allfolio.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    Optional<Holding> findByAsset_Id(UUID assetId);

    /** GET /v1/assets 목록 조회에서 자산별로 Holding을 따로 조회하는 N+1을 피하기 위한 배치 조회(docs/ROADMAP.md Task 012). */
    List<Holding> findByAsset_IdIn(Collection<UUID> assetIds);
}
