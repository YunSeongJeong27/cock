package com.depositcock.deposit;

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
 * 은행 (운영자가 넣는 값, 2026-10-27). 거래내역 헤더(컬럼)명은 파일 종류마다 BankHeader 에 따로 둔다.
 * 화면에서 수정하지 않으므로 setter 없음
 */
@Entity
@Table(name = "bank")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bank extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bankName;

    /** 공구 입금 계좌의 은행 이름과 맞춰 볼 다른 이름들 (쉼표 구분) */
    private String aliases;

    /** 목록에 없는 은행의 기본값 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultBank;

    @Column(nullable = false)
    private int sortOrder;
}
