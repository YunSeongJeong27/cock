package com.depositcock.deposit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "deposit_row")
public class DepositRow {

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

    /** 이 입금을 확정에 쓴 주문 id. 입금 1건은 주문 1건에만 쓴다. */
    private Long usedOrderId;

    protected DepositRow() {
    }

    public DepositRow(Long uploadId, int rowNo, String depositorName, int amount, String datetimeText,
                      LocalDateTime depositedAt) {
        this.uploadId = uploadId;
        this.rowNo = rowNo;
        this.depositorName = depositorName;
        this.amount = amount;
        this.datetimeText = datetimeText;
        this.depositedAt = depositedAt;
    }

    public void use(Long orderId) {
        this.usedOrderId = orderId;
    }

    public void release() {
        this.usedOrderId = null;
    }

    public String dedupeKey() {
        return dedupeKey(depositorName, amount, datetimeText);
    }

    /** 같은 입금인지 비교하는 키 (이름 + 금액 + 시각 원문) */
    public static String dedupeKey(String depositorName, int amount, String datetimeText) {
        return depositorName + "|" + amount + "|" + (datetimeText == null ? "" : datetimeText);
    }

    public Long getId() {
        return id;
    }

    public Long getUploadId() {
        return uploadId;
    }

    public int getRowNo() {
        return rowNo;
    }

    public String getDepositorName() {
        return depositorName;
    }

    public int getAmount() {
        return amount;
    }

    public String getDatetimeText() {
        return datetimeText;
    }

    public LocalDateTime getDepositedAt() {
        return depositedAt;
    }

    public Long getUsedOrderId() {
        return usedOrderId;
    }
}
