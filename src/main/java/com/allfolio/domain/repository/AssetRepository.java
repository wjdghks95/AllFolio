package com.allfolio.domain.repository;

import com.allfolio.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findAllByUser_Id(UUID userId);

    Optional<Asset> findByIdAndUser_Id(UUID id, UUID userId);
}
