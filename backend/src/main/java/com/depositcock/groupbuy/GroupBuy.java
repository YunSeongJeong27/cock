package com.depositcock.groupbuy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.depositcock.common.BaseTimeEntity;
import com.depositcock.common.Notice;
import com.depositcock.seller.Seller;
import jakarta.persistence.CascadeType;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "group_buy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupBuy extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id")
    private Seller seller;

    @Setter
    @Column(nullable = false)
    private String title;

    @Setter
    private String description;

    @Column(nullable = false, unique = true)
    private String publicToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupBuyStatus status;

    @Setter
    private LocalDateTime closeAt;

    @Setter
    private String bankName;
    @Setter
    private String accountNumber;
    @Setter
    private String accountHolder;

    /** 픽업을 고객에게 보일지 */
    @Setter
    @Column(nullable = false)
    private boolean pickupEnabled;

    /** 픽업 + 선입금 허용 */
    @Setter
    @Column(nullable = false)
    private boolean pickupPrepayEnabled;

    /** 픽업 + 현장결제(픽업 예약) 허용. 픽업의 기본값 (개발 전 인터뷰 결과 1번) */
    @Setter
    @Column(nullable = false)
    private boolean pickupOnsiteEnabled;

    /** 배송을 고객에게 보일지. 배송은 선입금만 (개발 전 인터뷰 결과 1번) */
    @Setter
    @Column(nullable = false)
    private boolean deliveryEnabled;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShippingFeeType shippingFeeType;

    @Setter
    @Column(nullable = false)
    private int shippingFee;

    /** 주문 받는 방법 (링크만이면 닉네임 선택, 링크 + 댓글이면 닉네임 필수) */
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderChannel orderChannel;

    @Setter
    @Column(nullable = false)
    private boolean unitAddressFieldEnabled;

    @Setter
    private String kakaoInquiryUrl;

    /** 2단계 댓글 집계에서 제외할 관리자 닉네임 (쉼표 구분) */
    @Setter
    private String adminNames;

    /** 주문 잠시 멈춤: 고객의 새 주문·수정·취소·환불 신청을 막는다 (내 주문 보기는 됨, 2026-10-18) */
    @Setter
    @Column(nullable = false)
    private boolean paused;

    /** 멈춤 중 고객 화면 문구 (비우면 기본 문구) */
    @Setter
    private String pauseMessage;

    /** 멈춤 중 고객 화면에 보여줄 링크 (밴드 글 등, 선택) */
    @Setter
    private String pauseLinkUrl;

    /** 마지막으로 준 주문번호. 판매자가 주문을 지워도 번호를 다시 쓰지 않게 (2026-10-19) */
    @Column(nullable = false)
    @Getter(AccessLevel.NONE)
    private int lastOrderNo;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Setter
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "groupBuy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Product> products = new ArrayList<>();

    @OneToMany(mappedBy = "groupBuy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PickupPoint> pickupPoints = new ArrayList<>();

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

    public void changeStatus(GroupBuyStatus status, LocalDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    /**
     * 다음 주문번호. 공구를 잠근 상태에서 부른다.
     *
     * @param maxExisting 지금 남아 있는 주문 중 가장 큰 번호 (이 칸이 생기기 전에 들어간 주문 대비)
     */
    public int nextOrderNo(int maxExisting) {
        this.lastOrderNo = Math.max(lastOrderNo, maxExisting) + 1;
        return lastOrderNo;
    }

    /** 고객 화면에 보일 문구 */
    public String effectivePauseMessage() {
        return pauseMessage == null ? Notice.PAUSE_DEFAULT.text() : pauseMessage;
    }

    /** 화면에 보일 상태 이름 */
    public String statusLabel(LocalDateTime now) {
        return status == GroupBuyStatus.OPEN && !isAcceptingOrders(now) ? GroupBuyStatus.CLOSE_AT_PASSED_LABEL : status.getLabel();
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

    /** 고객 주문서의 닉네임 필수 여부 (칸은 항상 보이고, 링크만 쓰는 공구에서는 선택) */
    public boolean isNicknameRequired() {
        return orderChannel == OrderChannel.LINK_AND_COMMENT;
    }

    public boolean isCommentOrdersEnabled() {
        return orderChannel == OrderChannel.LINK_AND_COMMENT;
    }
}
