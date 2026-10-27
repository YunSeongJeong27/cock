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

@Entity
@Table(name = "deposit_row")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositRow extends BaseTimeEntity {

    /** deposit_row.depositor_name·datetime_text 칸 길이 */
    public static final int MAX_DEPOSITOR_NAME = 200;
    public static final int MAX_DATETIME_TEXT = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long uploadId;

    @Column(nullable = false)
    private int rowNo;

    @Column(nullable = false)
    private String depositorName;

    @Column(nullable = false)
    private int amount;

    /** 은행 파일의 입금 시각 원문 */
    private String datetimeText;

    /** 변환한 입금 시각 (형식을 모르면 null) */
    private LocalDateTime depositedAt;

    /**
     * 판매자가 "이미 처리 완료된 입금"으로 옮긴 시각 (2026-10-26). 값이 있으면 대조에서 뺀다.
     * 지우지 않고 남겨서 같은 거래내역을 다시 올려도 중복으로 건너뛴다
     */
    private LocalDateTime handledAt;

    public DepositRow(Long uploadId, int rowNo, String depositorName, int amount, String datetimeText,
                      LocalDateTime depositedAt) {
        this.uploadId = uploadId;
        this.rowNo = rowNo;
        this.depositorName = depositorName;
        this.amount = amount;
        this.datetimeText = datetimeText;
        this.depositedAt = depositedAt;
    }

    public boolean isHandled() {
        return handledAt != null;
    }

    public void markHandled(LocalDateTime now) {
        this.handledAt = now;
    }

    public void unmarkHandled() {
        this.handledAt = null;
    }

    public String dedupeKey() {
        return dedupeKey(depositorName, amount, datetimeText);
    }

    /** 같은 입금인지 비교하는 키 (이름 + 금액 + 시각 원문) */
    public static String dedupeKey(String depositorName, int amount, String datetimeText) {
        return depositorName + "|" + amount + "|" + (datetimeText == null ? "" : datetimeText);
    }
}
