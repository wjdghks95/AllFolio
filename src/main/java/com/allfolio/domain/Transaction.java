package com.allfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** transactions 테이블 매핑 (V1__init.sql, Task 005). */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, updatable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 10)
    private TransactionType txType;

    @Column(name = "price", nullable = false, columnDefinition = "NUMERIC(28,8)")
    private BigDecimal price;

    @Column(name = "quantity", nullable = false, columnDefinition = "NUMERIC(28,8)")
    private BigDecimal quantity;

    @Column(name = "traded_at", nullable = false, updatable = false)
    private Instant tradedAt;

    protected Transaction() {
    }

    private Transaction(Asset asset, TransactionType txType, BigDecimal price, BigDecimal quantity, Instant tradedAt) {
        this.asset = asset;
        this.txType = txType;
        this.price = price;
        this.quantity = quantity;
        this.tradedAt = tradedAt;
    }

    public static Transaction of(Asset asset, TransactionType txType, BigDecimal price, BigDecimal quantity, Instant tradedAt) {
        return new Transaction(asset, txType, price, quantity, tradedAt);
    }

    public UUID getId() {
        return id;
    }

    public Asset getAsset() {
        return asset;
    }

    public TransactionType getTxType() {
        return txType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Instant getTradedAt() {
        return tradedAt;
    }
}
