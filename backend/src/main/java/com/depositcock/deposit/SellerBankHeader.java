package com.depositcock.deposit;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자가 은행·파일 종류별로 바꾼 헤더(컬럼)명, 또는 목록에 없어서 판매자가 추가한 값 (2026-10-27).
 * bankHeaderId 가 있으면 운영자 값(BankHeader)을 바꾼 것, 없으면 판매자가 추가한 은행·파일 종류
 */
@Entity
@Table(name = "seller_bank_header")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerBankHeader extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    private Long bankHeaderId;

    @Column(nullable = false)
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementType fileType;

    @Column(nullable = false)
    private String nameColumn;

    @Column(nullable = false)
    private String amountColumn;

    @Column(nullable = false)
    private String dateColumn;

    private String accountLabel;

    private String typeColumn;

    public SellerBankHeader(Long sellerId, Long bankHeaderId, String bankName, StatementType fileType) {
        this.sellerId = sellerId;
        this.bankHeaderId = bankHeaderId;
        this.bankName = bankName;
        this.fileType = fileType;
    }

    public void update(String nameColumn, String amountColumn, String dateColumn, String accountLabel, String typeColumn) {
        this.nameColumn = nameColumn;
        this.amountColumn = amountColumn;
        this.dateColumn = dateColumn;
        this.accountLabel = accountLabel;
        this.typeColumn = typeColumn;
    }
}
