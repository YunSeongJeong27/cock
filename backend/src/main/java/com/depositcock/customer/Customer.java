package com.depositcock.customer;

import java.time.LocalDateTime;

import com.depositcock.order.PhoneNumbers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자별 고객. 주문서 고객은 휴대폰 번호로, 댓글로만 주문한 고객은 댓글 닉네임으로 알아본다 (둘 중 하나만 값).
 * 같은 사람이 다른 판매자에게 주문하면 판매자마다 따로 생긴다 (판매자 사이에 고객 정보를 공유하지 않음).
 * 고객의 휴대폰 번호는 여기에만 둔다. 주문·기기·별칭은 이 고객의 id 를 가리킨다.
 */
@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    /** 정규화한 휴대폰 번호 (숫자만). 댓글 고객은 null */
    private String phone;

    /** 댓글 작성자 닉네임. 주문서 고객은 null */
    private String commentNickname;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private Customer(Long sellerId, String phone, String commentNickname, LocalDateTime now) {
        this.sellerId = sellerId;
        this.phone = phone;
        this.commentNickname = commentNickname;
        this.createdAt = now;
    }

    static Customer withPhone(Long sellerId, String normalizedPhone, LocalDateTime now) {
        return new Customer(sellerId, normalizedPhone, null, now);
    }

    static Customer withCommentNickname(Long sellerId, String nickname, LocalDateTime now) {
        return new Customer(sellerId, null, nickname, now);
    }

    /** 화면 표시용: "휴대폰 010-1234-5678" 또는 "닉네임 꼭지맘" */
    public String label() {
        return phone != null ? "휴대폰 " + PhoneNumbers.format(phone) : "닉네임 " + commentNickname;
    }
}
