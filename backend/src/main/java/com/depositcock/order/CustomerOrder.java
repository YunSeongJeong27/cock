package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.depositcock.customer.Customer;
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
import jakarta.persistence.OrderBy;
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

    /** 주문한 고객 (휴대폰 번호·댓글 닉네임은 고객에 있음) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

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

    /** 판매자가 주문 내용을 바꾼 기록 (오래된 순) */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<OrderChange> changes = new ArrayList<>();

    /** 입금 확정 이력 (확정 취소도 지우지 않고 남김). 살아 있는 확정은 최대 1건 */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<DepositConfirmation> confirmations = new ArrayList<>();

    @Column(nullable = false)
    private boolean onsitePaid;

    @Column(nullable = false)
    private boolean pickedUp;
    private LocalDateTime pickedUpAt;

    /** 입금 확정된 주문이 취소되면 true. 판매자가 "환불 완료"를 누르면 refundedAt 이 채워진다. */
    @Column(nullable = false)
    private boolean refundRequired;
    private LocalDateTime refundedAt;

    /**
     * 고객의 환불 신청 (입금 후 취소, 2026-10-18). 판매자가 [취소하고 환불]하면 취소 + 환불 필요,
     * [취소 안 함]이면 refundRejectedAt (주문은 그대로)
     */
    private LocalDateTime refundRequestedAt;
    private LocalDateTime refundRejectedAt;

    /** 환불받을 계좌. 계좌번호(refundAccount)는 암호화된 값. 환불 완료·반려·환불 없는 취소 때 지운다 */
    private String refundBank;
    private String refundAccount;
    private String refundHolder;

    /** 이 주문을 넣은 고객 기기의 쿠키 토큰. 그 기기에만 주문 내용·이전 입력값을 보여준다 (댓글 주문 등은 null) */
    private String deviceToken;

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

    public CustomerOrder(GroupBuy groupBuy, int orderNo, OrderSource source, Customer customer, String lookupToken,
                         LocalDateTime now) {
        this.groupBuy = groupBuy;
        this.orderNo = orderNo;
        this.source = source;
        this.customer = customer;
        this.lookupToken = lookupToken;
        this.status = OrderStatus.ORDERED;
        this.orderedAt = now;
        this.updatedAt = now;
    }

    public void fillCustomer(String depositorName, String nickname, String unitAddress) {
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

    /** 주문 내용을 바꿨다는 기록을 남긴다 */
    public void recordChange(String summary, ChangeActor actor, LocalDateTime now) {
        changes.add(new OrderChange(this, summary, actor, now));
        this.updatedAt = now;
    }

    public List<OrderChange> getChanges() {
        return changes;
    }

    /** 수량이 0 이 된 상품 줄은 지운다 (orphanRemoval) */
    void removeItem(OrderItem item) {
        items.remove(item);
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

    /**
     * 고객이 이 주문을 수정할 수 있는지 ([주문 수정] 또는 같은 휴대폰 번호로 다시 주문, 2026-10-18).
     * 주문 링크 주문이고, 취소·수령 전이고, 주문 확정 전 (2026-10-19 사용자 결정: 주문 확정 = 판매자가 대조해서 입금 확정한 상태.
     * 고객의 "입금했어요"는 실제 입금 여부를 알 수 없으므로 판단에 쓰지 않음)
     */
    public boolean isReplaceableByCustomer() {
        return isActive() && source == OrderSource.FORM && !isConfirmed() && !pickedUp;
    }

    /** 주문 확정 = 판매자가 입금 대조로(또는 수동으로) 입금 확정한 상태. 고객 수정·바로 취소는 확정 전에만 */
    public boolean isConfirmed() {
        return isPaid();
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
        if (isPaid()) {
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

    public void placedFrom(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    /** 입금 확정. 확정 기록을 새로 남기고 돌려준다 (depositRowId 는 수동 확정이면 null) */
    public DepositConfirmation confirmPaid(String depositorName, int amount, Long depositRowId, LocalDateTime now) {
        if (isPaid()) {
            throw new IllegalStateException("이미 입금 확정된 주문입니다: " + id);
        }
        DepositConfirmation confirmation = new DepositConfirmation(this, depositRowId, depositorName, amount, now);
        confirmations.add(confirmation);
        this.updatedAt = now;
        return confirmation;
    }

    /** 입금 확정 취소. 기록은 남기고 취소 시각만 채운다. 취소된 주문이면 "환불 필요"도 거둔다 (확정이 잘못이었으므로). */
    public void cancelPaid(LocalDateTime now) {
        if (status == OrderStatus.CANCELLED) {
            this.refundRequired = false;
            this.refundedAt = null;
            clearRefundAccount();
        }
        activeConfirmation().ifPresent(c -> c.cancel(now));
        this.updatedAt = now;
    }

    /** 살아 있는 입금 확정 (없으면 미입금) */
    public java.util.Optional<DepositConfirmation> activeConfirmation() {
        return confirmations.stream().filter(DepositConfirmation::isActive).findFirst();
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
        if (isPaid()) {
            this.refundRequired = true;
        }
        if (!refundRequired) {
            clearRefundAccount(); // 환불하지 않으면 계좌가 필요 없다
        }
    }

    /** 판매자가 환불 신청을 받아들임: 취소 + 환불 필요 (입금 확인 전 신고만 된 주문도 고객이 입금했다고 했으므로) */
    public void cancelForRefund(LocalDateTime now) {
        this.refundRequired = true;
        cancel(now);
    }

    public void markRefunded(LocalDateTime now) {
        this.refundedAt = now;
        this.updatedAt = now;
        clearRefundAccount();
    }

    /**
     * 고객의 환불 신청 (입금한 고객이 취소하려는 경우. 주문 확정 전후 언제든).
     *
     * @param encryptedAccount 암호화한 계좌번호
     */
    public void requestRefund(String bank, String encryptedAccount, String holder, LocalDateTime now) {
        this.refundRequestedAt = now;
        this.refundRejectedAt = null;
        this.refundBank = bank;
        this.refundAccount = encryptedAccount;
        this.refundHolder = holder;
        this.updatedAt = now;
    }

    /** 판매자가 환불 신청을 받아들이지 않음 (주문은 그대로). 계좌는 지운다 */
    public void rejectRefund(LocalDateTime now) {
        this.refundRejectedAt = now;
        clearRefundAccount();
        this.updatedAt = now;
    }

    /** 판매자 확인을 기다리는 환불 신청이 있는지 */
    public boolean isRefundPending() {
        return isActive() && refundRequestedAt != null && refundRejectedAt == null;
    }

    /**
     * 고객이 환불 신청을 할 수 있는지: 주문 링크의 선입금 주문, 취소·수령 전, 아직 신청한 적 없음 (반려된 뒤 다시 신청은 안 받음).
     * 마감 뒤에도 된다 (판매자가 확인하므로, 2026-10-18)
     */
    public boolean canRequestRefund() {
        return isActive() && source == OrderSource.FORM && isPrepaid() && !pickedUp && refundRequestedAt == null;
    }

    private void clearRefundAccount() {
        this.refundBank = null;
        this.refundAccount = null;
        this.refundHolder = null;
    }

    public LocalDateTime getRefundRequestedAt() {
        return refundRequestedAt;
    }

    public LocalDateTime getRefundRejectedAt() {
        return refundRejectedAt;
    }

    public String getRefundBank() {
        return refundBank;
    }

    /** 암호화된 계좌번호 */
    public String getRefundAccount() {
        return refundAccount;
    }

    public String getRefundHolder() {
        return refundHolder;
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

    public Customer getCustomer() {
        return customer;
    }

    public Long getCustomerId() {
        return customer.getId();
    }

    /** 주문한 고객의 휴대폰 번호 (댓글 주문은 null) */
    public String getPhone() {
        return customer.getPhone();
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
        return activeConfirmation().isPresent();
    }

    public LocalDateTime getPaidAt() {
        return activeConfirmation().map(DepositConfirmation::getConfirmedAt).orElse(null);
    }

    public String getPaidDepositorName() {
        return activeConfirmation().map(DepositConfirmation::getDepositorName).orElse(null);
    }

    public List<DepositConfirmation> getConfirmations() {
        return confirmations;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public Integer getPaidAmount() {
        return activeConfirmation().map(DepositConfirmation::getAmount).orElse(null);
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
