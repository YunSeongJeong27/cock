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

@Entity
@Table(name = "pickup_point")
public class PickupPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_buy_id")
    private GroupBuy groupBuy;

    @Column(nullable = false)
    private String name;

    private String info;

    @Column(nullable = false)
    private int sortOrder;

    protected PickupPoint() {
    }

    public PickupPoint(GroupBuy groupBuy, String name, String info, int sortOrder) {
        this.groupBuy = groupBuy;
        this.name = name;
        this.info = info;
        this.sortOrder = sortOrder;
    }

    public void update(String name, String info, int sortOrder) {
        this.name = name;
        this.info = info;
        this.sortOrder = sortOrder;
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

    public String getInfo() {
        return info;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
