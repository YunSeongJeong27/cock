package com.depositcock.groupbuy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_buy_id")
    private GroupBuy groupBuy;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(nullable = false)
    private int unitPrice;

    /** 준비 수량(재고). null 이면 제한 없음. 넘어도 주문은 막지 않는다 (2026-10-16) */
    @Setter
    private Integer stockLimit;

    /** 남은 수량이 이 값 이하가 되면 판매자에게 알림 (화면·메일). null 이면 알림 없음 */
    @Setter
    private Integer stockAlertThreshold;

    /** 알림 메일을 보냈는지. 남은 수량이 기준보다 다시 많아지면 false 로 돌아가 다음에 또 보낸다 (StockAlerts 만 바꿈) */
    @Setter(AccessLevel.PACKAGE)
    @Column(nullable = false)
    private boolean stockAlertSent;

    /** 켜면 준비 수량에 도달했을 때 품절로 주문을 막는다 (판매자 선택, 기본 꺼짐. 2026-10-16) */
    @Setter
    @Column(nullable = false)
    private boolean blockWhenSoldOut;

    /**
     * 판매된 수량. 주문/취소 시 ProductRepository 의 원자 UPDATE 로만 바꾼다.
     * updatable = false: 판매자가 상품을 수정할 때 메모리의 옛 값으로 덮어써서 재고가 틀어지는 것 방지.
     */
    @Column(nullable = false, updatable = false)
    private int sold;

    /** null 이면 1인 최대 수량 제한 없음 */
    @Setter
    private Integer perOrderLimit;

    @Setter
    @Column(nullable = false)
    private int sortOrder;

    @Version
    @Getter(AccessLevel.NONE)
    private long version;

    @Builder
    public Product(GroupBuy groupBuy, String name, int unitPrice, Integer stockLimit, Integer perOrderLimit, int sortOrder,
                   Integer stockAlertThreshold, boolean blockWhenSoldOut) {
        this.groupBuy = groupBuy;
        this.name = name;
        this.unitPrice = unitPrice;
        this.stockLimit = stockLimit;
        this.perOrderLimit = perOrderLimit;
        this.sortOrder = sortOrder;
        this.stockAlertThreshold = stockAlertThreshold;
        this.blockWhenSoldOut = blockWhenSoldOut;
    }

    /** 품절로 막는 중인지: "다 팔리면 품절"을 켰고 준비 수량에 도달함 */
    public boolean isSoldOut() {
        return blockWhenSoldOut && stockLimit != null && sold >= stockLimit;
    }

    /** 판매자용 남은 수량. 재고보다 더 주문되면 음수 (예: -3 = 3개 초과). 재고 제한이 없으면 null */
    public Integer remaining() {
        return stockLimit == null ? null : stockLimit - sold;
    }

    /**
     * 손님 화면용 남은 수량: 1개 이상일 때만 보여 주고, 0 이하면 null (막지 않는 상품은 수량 표시를 숨기고 주문을 받음,
     * "다 팔리면 품절"을 켠 상품은 품절로 표시).
     * "1개 남음"처럼 거짓으로 보이게 하지 않는다 (공정위 다크패턴 가이드라인의 거짓 재고 표시 주의, 2026-10-16 사용자 결정)
     */
    public Integer publicRemaining() {
        Integer remaining = remaining();
        return remaining == null || remaining < 1 ? null : remaining;
    }
}
