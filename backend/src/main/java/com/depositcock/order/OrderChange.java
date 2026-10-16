package com.depositcock.order;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 판매자가 주문 내용을 바꾼 기록 1건 (사람이 읽는 문장) */
@Entity
@Table(name = "order_change")
public class OrderChange {

    /** order_change.summary 칸 길이 */
    static final int MAX_SUMMARY = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private CustomerOrder order;

    @Column(nullable = false)
    private String summary;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    protected OrderChange() {
    }

    OrderChange(CustomerOrder order, String summary, LocalDateTime changedAt) {
        this.order = order;
        // 상품이 아주 많은 공구에서 한꺼번에 바꾸면 칸 길이를 넘을 수 있어 자른다
        this.summary = summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY - 1) + "…" : summary;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
