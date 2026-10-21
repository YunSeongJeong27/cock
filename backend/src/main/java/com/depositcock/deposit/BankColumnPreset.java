package com.depositcock.deposit;

import java.time.LocalDateTime;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자가 저장한 은행별 열 이름 (예: "신협" → 이름 열 "기재내용", 금액 열 "입금액").
 */
@Entity
@Table(name = "bank_column_preset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankColumnPreset extends BaseTimeEntity {

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
}
