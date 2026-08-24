package com.allfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** holdings 테이블 매핑. @Version 낙관적 잠금 (V1__init.sql, Task 005). */
@Entity
@Table(name = "holdings")
public class Holding {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, updatable = false)
    private Asset asset;

    @Column(name = "quantity", nullable = false, columnDefinition = "NUMERIC(28,8)")
    private BigDecimal quantity;

    @Column(name = "avg_price", nullable = false, columnDefinition = "NUMERIC(28,8)")
    private BigDecimal avgPrice;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Holding() {
    }

    private Holding(Asset asset, BigDecimal quantity, BigDecimal avgPrice, Instant updatedAt) {
        this.asset = asset;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
        this.updatedAt = updatedAt;
    }

    public static Holding of(Asset asset, BigDecimal quantity, BigDecimal avgPrice) {
        return new Holding(asset, quantity, avgPrice, Instant.now());
    }

    /**
     * PUT /v1/assets/{id}/holdings 반영 (docs/ROADMAP.md Task 012). version은 건드리지 않는다 —
     * Hibernate가 flush 시 @Version 필드를 자동으로 1 증가시킨다.
     */
    public void update(BigDecimal quantity, BigDecimal avgPrice) {
        this.quantity = quantity;
        this.avgPrice = avgPrice;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Asset getAsset() {
        return asset;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getAvgPrice() {
        return avgPrice;
    }

    public int getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
