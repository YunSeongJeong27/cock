package com.depositcock.order;

import java.time.LocalDateTime;

import com.depositcock.common.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문 내용을 바꾼 기록 1건 (사람이 읽는 문장). 판매자 "주문 변경" 또는 고객이 같은 번호로 다시 주문한 경우 */
@Entity
@Table(name = "order_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderChange extends BaseTimeEntity {

    /** order_change.summary 칸 길이 */
    static final int MAX_SUMMARY = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    @Getter(AccessLevel.NONE)
    private CustomerOrder order;

    @Column(nullable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeActor changedBy;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    OrderChange(CustomerOrder order, String summary, ChangeActor changedBy, LocalDateTime changedAt) {
        this.order = order;
        // 상품이 아주 많은 공구에서 한꺼번에 바꾸면 칸 길이를 넘을 수 있어 자른다
        this.summary = summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY - 1) + "…" : summary;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }
}
