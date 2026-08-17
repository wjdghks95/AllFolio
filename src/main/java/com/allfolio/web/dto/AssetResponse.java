package com.allfolio.web.dto;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;

import java.time.Instant;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        String ticker,
        String name,
        AssetType assetType,
        String currency,
        String quantity,
        String avgPrice,
        int version,
        Instant updatedAt
) {

    public static AssetResponse from(Asset asset, Holding holding) {
        return new AssetResponse(
                asset.getId(),
                asset.getTicker(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCurrency(),
                holding.getQuantity().toPlainString(),
                holding.getAvgPrice().toPlainString(),
                holding.getVersion(),
                holding.getUpdatedAt()
        );
    }
}
