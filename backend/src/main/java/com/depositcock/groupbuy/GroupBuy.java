package com.depositcock.groupbuy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.depositcock.seller.Seller;
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

@Entity
@DynamicUpdate
@Table(name = "group_buy")
public class GroupBuy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id")
    private Seller seller;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false, unique = true)
    private String publicToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupBuyStatus status;

    private LocalDateTime closeAt;

    private String bankName;
    private String accountNumber;
    private String accountHolder;

    /** 픽업을 고객에게 보일지 */
    @Column(nullable = false)
    private boolean pickupEnabled;

    /** 픽업 + 선입금 허용 */
    @Column(nullable = false)
    private boolean pickupPrepayEnabled;

    /** 픽업 + 현장결제(픽업 예약) 허용. 픽업의 기본값 (개발 전 인터뷰 결과 1번) */
    @Column(nullable = false)
    private boolean pickupOnsiteEnabled;

    /** 배송을 고객에게 보일지. 배송은 선입금만 (개발 전 인터뷰 결과 1번) */
    @Column(nullable = false)
    private boolean deliveryEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShippingFeeType shippingFeeType;

    @Column(nullable = false)
    private int shippingFee;

    /** 주문 받는 방법 (링크만이면 닉네임 선택, 링크 + 댓글이면 닉네임 필수) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderChannel orderChannel;

    @Column(nullable = false)
    private boolean unitAddressFieldEnabled;

    private String kakaoInquiryUrl;

    /** 2단계 댓글 집계에서 제외할 관리자 닉네임 (쉼표 구분) */
    private String adminNames;

    /** 주문 잠시 멈춤: 고객의 새 주문·수정·취소·환불 신청을 막는다 (내 주문 보기는 됨, 2026-10-18) */
    @Column(nullable = false)
    private boolean paused;

    /** 멈춤 중 고객 화면 문구 (비우면 기본 문구) */
    private String pauseMessage;

    /** 멈춤 중 고객 화면에 보여줄 링크 (밴드 글 등, 선택) */
    private String pauseLinkUrl;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "groupBuy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Product> products = new ArrayList<>();

    @OneToMany(mappedBy = "groupBuy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PickupPoint> pickupPoints = new ArrayList<>();

    protected GroupBuy() {
    }

    public GroupBuy(Seller seller, String publicToken, LocalDateTime now) {
        this.seller = seller;
        this.publicToken = publicToken;
        this.status = GroupBuyStatus.DRAFT;
        this.pickupEnabled = true;
        this.pickupOnsiteEnabled = true;
        this.shippingFeeType = ShippingFeeType.NONE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateSettings(GroupBuySettings s, LocalDateTime now) {
        this.title = s.title();
        this.description = s.description();
        this.closeAt = s.closeAt();
        this.bankName = s.bankName();
        this.accountNumber = s.accountNumber();
        this.accountHolder = s.accountHolder();
        this.pickupEnabled = s.pickupEnabled();
        this.pickupPrepayEnabled = s.pickupPrepayEnabled();
        this.pickupOnsiteEnabled = s.pickupOnsiteEnabled();
        this.deliveryEnabled = s.deliveryEnabled();
        this.shippingFeeType = s.shippingFeeType();
        this.shippingFee = s.shippingFeeType() == ShippingFeeType.FIXED ? s.shippingFee() : 0;
        this.orderChannel = s.orderChannel();
        this.unitAddressFieldEnabled = s.unitAddressFieldEnabled();
        this.kakaoInquiryUrl = s.kakaoInquiryUrl();
        this.adminNames = s.adminNames();
        this.updatedAt = now;
    }

    public void changeStatus(GroupBuyStatus status, LocalDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    /** 멈춤 기본 문구 (2026-10-18 사용자 결정) */
    public static final String DEFAULT_PAUSE_MESSAGE = "현재 주문 집계 중입니다. 판매자에게 문의해 주시면 감사하겠습니다.";

    /** 멈추거나 푼다. 문구·링크는 풀어도 남겨서 다음에 다시 쓴다 */
    public void changePause(boolean paused, String message, String linkUrl, LocalDateTime now) {
        this.paused = paused;
        this.pauseMessage = message;
        this.pauseLinkUrl = linkUrl;
        this.updatedAt = now;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getPauseMessage() {
        return pauseMessage;
    }

    /** 고객 화면에 보일 문구 */
    public String effectivePauseMessage() {
        return pauseMessage == null ? DEFAULT_PAUSE_MESSAGE : pauseMessage;
    }

    public String getPauseLinkUrl() {
        return pauseLinkUrl;
    }

    /** 고객이 지금 주문·취소할 수 있는지 (OPEN 이고 마감 시각 전) */
    public boolean isAcceptingOrders(LocalDateTime now) {
        return status == GroupBuyStatus.OPEN && (closeAt == null || now.isBefore(closeAt));
    }

    /** 선입금 주문이 생길 수 있는 설정인지 (배송, 또는 픽업 선입금) */
    public boolean usesPrepay() {
        return deliveryEnabled || (pickupEnabled && pickupPrepayEnabled);
    }

    public List<String> adminNameList() {
        if (adminNames == null || adminNames.isBlank()) {
            return List.of();
        }
        return Arrays.stream(adminNames.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
    }

    public List<Product> sortedProducts() {
        return products.stream().sorted(Comparator.comparingInt(Product::getSortOrder).thenComparing(Product::getId,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    public List<PickupPoint> sortedPickupPoints() {
        return pickupPoints.stream().sorted(Comparator.comparingInt(PickupPoint::getSortOrder).thenComparing(
                PickupPoint::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    public Long getId() {
        return id;
    }

    public Seller getSeller() {
        return seller;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public GroupBuyStatus getStatus() {
        return status;
    }

    public LocalDateTime getCloseAt() {
        return closeAt;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountHolder() {
        return accountHolder;
    }

    public boolean isPickupEnabled() {
        return pickupEnabled;
    }

    public boolean isPickupPrepayEnabled() {
        return pickupPrepayEnabled;
    }

    public boolean isPickupOnsiteEnabled() {
        return pickupOnsiteEnabled;
    }

    public boolean isDeliveryEnabled() {
        return deliveryEnabled;
    }

    public ShippingFeeType getShippingFeeType() {
        return shippingFeeType;
    }

    public int getShippingFee() {
        return shippingFee;
    }

    public OrderChannel getOrderChannel() {
        return orderChannel;
    }

    /** 고객 주문서의 닉네임 필수 여부 (칸은 항상 보이고, 링크만 쓰는 공구에서는 선택) */
    public boolean isNicknameRequired() {
        return orderChannel == OrderChannel.LINK_AND_COMMENT;
    }

    public boolean isCommentOrdersEnabled() {
        return orderChannel == OrderChannel.LINK_AND_COMMENT;
    }

    public boolean isUnitAddressFieldEnabled() {
        return unitAddressFieldEnabled;
    }

    public String getKakaoInquiryUrl() {
        return kakaoInquiryUrl;
    }

    public String getAdminNames() {
        return adminNames;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<Product> getProducts() {
        return products;
    }

    public List<PickupPoint> getPickupPoints() {
        return pickupPoints;
    }
}
