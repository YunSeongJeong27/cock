package com.depositcock.order;

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

@Entity
@Table(name = "order_item")
public class OrderItem {

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

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int unitPriceSnapshot;

    protected OrderItem() {
    }

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

    public Long getId() {
        return id;
    }

    public CustomerOrder getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }
}
