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

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_buy_id")
    private GroupBuy groupBuy;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int unitPrice;

    /** null 이면 재고 제한 없음 */
    private Integer stockLimit;

    /** 판매된 수량. 주문/취소 시 ProductRepository 의 원자 UPDATE 로만 바꾼다. */
    @Column(nullable = false)
    private int sold;

    /** null 이면 1인 최대 수량 제한 없음 */
    private Integer perOrderLimit;

    @Column(nullable = false)
    private int sortOrder;

    @Version
    private long version;

    protected Product() {
    }

    public Product(GroupBuy groupBuy, String name, int unitPrice, Integer stockLimit, Integer perOrderLimit, int sortOrder) {
        this.groupBuy = groupBuy;
        this.name = name;
        this.unitPrice = unitPrice;
        this.stockLimit = stockLimit;
        this.perOrderLimit = perOrderLimit;
        this.sortOrder = sortOrder;
    }

    public void update(String name, int unitPrice, Integer stockLimit, Integer perOrderLimit, int sortOrder) {
        this.name = name;
        this.unitPrice = unitPrice;
        this.stockLimit = stockLimit;
        this.perOrderLimit = perOrderLimit;
        this.sortOrder = sortOrder;
    }

    public boolean isSoldOut() {
        return stockLimit != null && sold >= stockLimit;
    }

    public Integer remaining() {
        return stockLimit == null ? null : Math.max(0, stockLimit - sold);
    }

    public Long getId() {
        return id;
    }

    public GroupBuy getGroupBuy() {
        return groupBuy;
    }

    public String getName() {
        return name;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public Integer getStockLimit() {
        return stockLimit;
    }

    public int getSold() {
        return sold;
    }

    public Integer getPerOrderLimit() {
        return perOrderLimit;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
