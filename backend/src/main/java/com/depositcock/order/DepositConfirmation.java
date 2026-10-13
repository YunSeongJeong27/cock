package com.depositcock.order;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 입금 확정 1건. 확정을 취소해도 지우지 않고 cancelledAt 을 채워 이력으로 남긴다 (누가 언제 무엇으로 확정했다가 취소했는지).
 * activeOrderId / activeDepositRowId 는 살아 있는 확정에만 값이 있어서, DB 유일 제약이
 * "주문 1건에 살아 있는 확정 1건", "입금 1건은 주문 1건에만"을 지킨다.
 */
@Entity
@Table(name = "deposit_confirmation")
public class DepositConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private CustomerOrder order;

    /** 확정에 쓴 입금 줄. 수동 확정이거나 업로드를 지웠으면 null */
    private Long depositRowId;

    /** 거래내역 없이 판매자가 직접 확정했는지 */
    @Column(nullable = false)
    private boolean manual;

    @Column(nullable = false)
    private String depositorName;

    @Column(nullable = false)
    private int amount;

    /** 이 확정이 입금자 별칭을 새로 만들었는지·바꿨는지 (null 이면 건드리지 않음) */
    @Enumerated(EnumType.STRING)
    private PaidAliasChange aliasChange;

    /** aliasChange 가 CHANGED 일 때 바꾸기 전 고객 키 */
    private String aliasPreviousKey;

    @Column(nullable = false)
    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    private Long activeOrderId;

    private Long activeDepositRowId;

    protected DepositConfirmation() {
    }

    DepositConfirmation(CustomerOrder order, Long depositRowId, String depositorName, int amount, LocalDateTime now) {
        this.order = order;
        this.depositRowId = depositRowId;
        this.manual = depositRowId == null;
        this.depositorName = depositorName;
        this.amount = amount;
        this.confirmedAt = now;
        this.activeOrderId = order.getId();
        this.activeDepositRowId = depositRowId;
    }

    /** 확정 때 별칭을 만들었거나 바꿨으면 기록한다 */
    public void recordAliasChange(PaidAliasChange change, String previousKey) {
        this.aliasChange = change;
        this.aliasPreviousKey = previousKey;
    }

    void cancel(LocalDateTime now) {
        this.cancelledAt = now;
        this.activeOrderId = null;
        this.activeDepositRowId = null;
    }

    public boolean isActive() {
        return cancelledAt == null;
    }

    public Long getId() {
        return id;
    }

    public CustomerOrder getOrder() {
        return order;
    }

    public Long getDepositRowId() {
        return depositRowId;
    }

    public boolean isManual() {
        return manual;
    }

    public String getDepositorName() {
        return depositorName;
    }

    public int getAmount() {
        return amount;
    }

    public PaidAliasChange getAliasChange() {
        return aliasChange;
    }

    public String getAliasPreviousKey() {
        return aliasPreviousKey;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }
}
