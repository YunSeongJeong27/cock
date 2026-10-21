package com.depositcock.order;

import com.depositcock.common.BaseTimeEntity;
import com.depositcock.groupbuy.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.Setter;

@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private String productNameSnapshot;

    /** 주문 변경(OrderUpdate)에서만 바꾼다. 단가는 주문 당시 그대로 */
    @Setter(AccessLevel.PACKAGE)
    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int unitPriceSnapshot;

    public OrderItem(CustomerOrder order, Product product, int quantity) {
        this.order = order;
        this.product = product;
        this.productNameSnapshot = product.getName();
        this.quantity = quantity;
        this.unitPriceSnapshot = product.getUnitPrice();
    }

    public int amount() {
        return quantity * unitPriceSnapshot;
    }
}
