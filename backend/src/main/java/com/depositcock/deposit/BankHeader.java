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
 * 은행·파일 종류별 거래내역 헤더(컬럼)명: 운영자가 은행마다 조사해 넣는 값 (2026-10-27 사용자 결정).
 * 판매자는 바꾸지 못하고, 판매자가 바꾼 값은 SellerBankHeader 에 따로 둔다. 화면에서 수정하지 않으므로 setter 없음
 */
@Entity
@Table(name = "bank_header")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankHeader extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bankId;

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
}
