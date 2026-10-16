package com.depositcock.deposit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 판매자가 저장한 은행별 열 이름 (예: "신협" → 이름 열 "기재내용", 금액 열 "입금액").
 */
@Entity
@Table(name = "bank_column_preset")
public class BankColumnPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private String name;

    private String dateColumn;

    @Column(nullable = false)
    private String nameColumn;

    @Column(nullable = false)
    private String amountColumn;

    private String typeColumn;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected BankColumnPreset() {
    }

    public BankColumnPreset(Long sellerId, String name, ColumnMapping mapping, LocalDateTime createdAt) {
        this.sellerId = sellerId;
        this.name = name;
        update(mapping);
        this.createdAt = createdAt;
    }

    public void update(ColumnMapping mapping) {
        this.dateColumn = mapping.dateColumn();
        this.nameColumn = mapping.nameColumn();
        this.amountColumn = mapping.amountColumn();
        this.typeColumn = mapping.typeColumn();
    }

    public ColumnMapping toMapping() {
        return new ColumnMapping(dateColumn, nameColumn, amountColumn, typeColumn);
    }

    public Long getId() {
        return id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public String getName() {
        return name;
    }

    public String getDateColumn() {
        return dateColumn;
    }

    public String getNameColumn() {
        return nameColumn;
    }

    public String getAmountColumn() {
        return amountColumn;
    }

    public String getTypeColumn() {
        return typeColumn;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
