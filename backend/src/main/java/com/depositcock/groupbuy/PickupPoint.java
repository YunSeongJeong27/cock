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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pickup_point")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickupPoint {

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
    private String info;

    @Setter
    @Column(nullable = false)
    private int sortOrder;

    public PickupPoint(GroupBuy groupBuy, String name, String info, int sortOrder) {
        this.groupBuy = groupBuy;
        this.name = name;
        this.info = info;
        this.sortOrder = sortOrder;
    }
}
