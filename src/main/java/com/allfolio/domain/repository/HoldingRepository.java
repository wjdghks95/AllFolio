package com.allfolio.domain.repository;

import com.allfolio.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    Optional<Holding> findByAsset_Id(UUID assetId);
}
