package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.PickupPoint;
import jakarta.persistence.CascadeType;
import org.hibernate.annotations.DynamicUpdate;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 주문 1건. 입금·수령 상태는 주문 단위로 관리한다 (개발 전 인터뷰 결과 3번).
 */
@Entity
@DynamicUpdate
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_buy_id")
    private GroupBuy groupBuy;

    /** 공구 안에서의 주문번호 (1, 2, 3 ...) */
    @Column(nullable = false)
    private int orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSource source;

    /** 고객 키: 주문서 = 정규화한 휴대폰 번호, 댓글 = "nick:" + 닉네임 */
    @Column(nullable = false)
    private String customerKey;

    private String phone;
    private String depositorName;
    private String nickname;
    private String unitAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiveMethod receiveMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_point_id")
    private PickupPoint pickupPoint;

    private String recipientName;
    private String recipientPhone;
    private String zipcode;
    private String address;
    private String addressDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Column(nullable = false)
    private int itemsAmount;

    @Column(nullable = false)
    private int shippingFee;

    @Column(nullable = false)
    private int expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /** 고객이 "입금했어요"를 누른 시각 (확정 아님) */
    private LocalDateTime depositReportedAt;

    @Column(nullable = false)
    private boolean paid;
    private LocalDateTime paidAt;
    private String paidDepositorName;
    private Integer paidAmount;
    private Long depositRowId;
    /** 이 확정이 별칭을 새로 만들었는지·바꿨는지. 확정 취소 때 이것만 되돌린다 (null 이면 건드리지 않음) */
    @Enumerated(EnumType.STRING)
    private PaidAliasChange paidAliasChange;
    /** paidAliasChange 가 CHANGED 일 때 바꾸기 전 고객 키 */
    private String paidAliasPreviousKey;

    @Column(nullable = false)
    private boolean onsitePaid;

    @Column(nullable = false)
    private boolean pickedUp;
    private LocalDateTime pickedUpAt;

    /** 입금 확정된 주문이 취소되면 true. 판매자가 "환불 완료"를 누르면 refundedAt 이 채워진다. */
    @Column(nullable = false)
    private boolean refundRequired;
    private LocalDateTime refundedAt;

    /** 내부용 랜덤 토큰 (고객에게 안내하지 않음) */
    @Column(nullable = false, unique = true)
    private String lookupToken;

    @Column(nullable = false)
    private LocalDateTime orderedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected CustomerOrder() {
    }

    public CustomerOrder(GroupBuy groupBuy, int orderNo, OrderSource source, String customerKey, String lookupToken,
                         LocalDateTime now) {
        this.groupBuy = groupBuy;
        this.orderNo = orderNo;
        this.source = source;
        this.customerKey = customerKey;
        this.lookupToken = lookupToken;
        this.status = OrderStatus.ORDERED;
        this.orderedAt = now;
        this.updatedAt = now;
    }

    public void fillCustomer(String phone, String depositorName, String nickname, String unitAddress) {
        this.phone = phone;
        this.depositorName = depositorName;
        this.nickname = nickname;
        this.unitAddress = unitAddress;
    }

    public void fillReceive(ReceiveMethod receiveMethod, PickupPoint pickupPoint, PaymentMethod paymentMethod) {
        this.receiveMethod = receiveMethod;
        this.pickupPoint = pickupPoint;
        this.paymentMethod = paymentMethod;
    }

    public void fillDelivery(String recipientName, String recipientPhone, String zipcode, String address,
                             String addressDetail) {
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipcode = zipcode;
        this.address = address;
        this.addressDetail = addressDetail;
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }

    public void fillAmounts(int itemsAmount, int shippingFee) {
        this.itemsAmount = itemsAmount;
        this.shippingFee = shippingFee;
        this.expectedAmount = itemsAmount + shippingFee;
    }

    public boolean isActive() {
        return status == OrderStatus.ORDERED;
    }

    public boolean isPrepaid() {
        return paymentMethod == PaymentMethod.PREPAID;
    }

    public DisplayStatus displayStatus() {
        if (status == OrderStatus.CANCELLED) {
            return DisplayStatus.CANCELLED;
        }
        if (pickedUp) {
            return DisplayStatus.PICKED_UP;
        }
        if (paymentMethod == PaymentMethod.ONSITE) {
            return DisplayStatus.RESERVED;
        }
        if (paid) {
            return DisplayStatus.PAID;
        }
        if (depositReportedAt != null) {
            return DisplayStatus.DEPOSIT_REPORTED;
        }
        return DisplayStatus.AWAITING_DEPOSIT;
    }

    public void reportDeposit(LocalDateTime now) {
        if (depositReportedAt == null) {
            depositReportedAt = now;
            updatedAt = now;
        }
    }

    public void confirmPaid(String depositorName, int amount, Long depositRowId, LocalDateTime now) {
        this.paid = true;
        this.paidAt = now;
        this.paidDepositorName = depositorName;
        this.paidAmount = amount;
        this.depositRowId = depositRowId;
        this.paidAliasChange = null;
        this.paidAliasPreviousKey = null;
        this.updatedAt = now;
    }

    /** 입금 확정 때 별칭을 만들었거나 바꿨으면 기록한다 */
    public void recordAliasChange(PaidAliasChange change, String previousKey) {
        this.paidAliasChange = change;
        this.paidAliasPreviousKey = previousKey;
    }

    /** 입금 확정 취소. 취소된 주문이면 "환불 필요"도 거둔다 (확정이 잘못이었으므로). */
    public void cancelPaid(LocalDateTime now) {
        if (status == OrderStatus.CANCELLED) {
            this.refundRequired = false;
            this.refundedAt = null;
        }
        this.paid = false;
        this.paidAt = null;
        this.paidDepositorName = null;
        this.paidAmount = null;
        this.depositRowId = null;
        this.paidAliasChange = null;
        this.paidAliasPreviousKey = null;
        this.updatedAt = now;
    }

    /** 수령 체크. 현장결제 주문은 수령할 때 결제 완료로 처리한다. */
    public void markPickedUp(boolean pickedUp, LocalDateTime now) {
        this.pickedUp = pickedUp;
        this.pickedUpAt = pickedUp ? now : null;
        if (paymentMethod == PaymentMethod.ONSITE) {
            this.onsitePaid = pickedUp;
        }
        this.updatedAt = now;
    }

    public void cancel(LocalDateTime now) {
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
        if (paid) {
            this.refundRequired = true;
        }
    }

    public void markRefunded(LocalDateTime now) {
        this.refundedAt = now;
        this.updatedAt = now;
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    public Long getId() {
        return id;
    }

    public GroupBuy getGroupBuy() {
        return groupBuy;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public OrderSource getSource() {
        return source;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public String getPhone() {
        return phone;
    }

    public String getDepositorName() {
        return depositorName;
    }

    public String getNickname() {
        return nickname;
    }

    public String getUnitAddress() {
        return unitAddress;
    }

    public ReceiveMethod getReceiveMethod() {
        return receiveMethod;
    }

    public PickupPoint getPickupPoint() {
        return pickupPoint;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public String getZipcode() {
        return zipcode;
    }

    public String getAddress() {
        return address;
    }

    public String getAddressDetail() {
        return addressDetail;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public int getItemsAmount() {
        return itemsAmount;
    }

    public int getShippingFee() {
        return shippingFee;
    }

    public int getExpectedAmount() {
        return expectedAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getDepositReportedAt() {
        return depositReportedAt;
    }

    public boolean isPaid() {
        return paid;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public String getPaidDepositorName() {
        return paidDepositorName;
    }

    public PaidAliasChange getPaidAliasChange() {
        return paidAliasChange;
    }

    public String getPaidAliasPreviousKey() {
        return paidAliasPreviousKey;
    }

    public Integer getPaidAmount() {
        return paidAmount;
    }

    public Long getDepositRowId() {
        return depositRowId;
    }

    public boolean isOnsitePaid() {
        return onsitePaid;
    }

    public boolean isPickedUp() {
        return pickedUp;
    }

    public LocalDateTime getPickedUpAt() {
        return pickedUpAt;
    }

    public boolean isRefundRequired() {
        return refundRequired;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public String getLookupToken() {
        return lookupToken;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
