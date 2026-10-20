package com.depositcock.customer;

import java.time.LocalDateTime;

import com.depositcock.seller.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자별 고객을 찾거나 새로 만든다.
 * 같은 판매자의 다른 공구에서 같은 번호로 동시에 처음 주문해도 고객이 두 번 생기지 않게, 새로 만들 때만 판매자 행을 잠그고
 * 다시 찾아본다 (잠금 순서는 공구 → 판매자로 고정).
 */
@Component
@RequiredArgsConstructor
public class Customers {

    private final CustomerRepository repository;
    private final SellerRepository sellerRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Customer byPhone(Long sellerId, String normalizedPhone, LocalDateTime now) {
        return repository.findBySellerIdAndPhone(sellerId, normalizedPhone).orElseGet(() -> {
            sellerRepository.findByIdForUpdate(sellerId);
            return repository.findBySellerIdAndPhone(sellerId, normalizedPhone)
                    .orElseGet(() -> repository.save(Customer.withPhone(sellerId, normalizedPhone, now)));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Customer byCommentNickname(Long sellerId, String nickname, LocalDateTime now) {
        return repository.findBySellerIdAndCommentNickname(sellerId, nickname).orElseGet(() -> {
            sellerRepository.findByIdForUpdate(sellerId);
            return repository.findBySellerIdAndCommentNickname(sellerId, nickname)
                    .orElseGet(() -> repository.save(Customer.withCommentNickname(sellerId, nickname, now)));
        });
    }

    /** 주문이 하나도 남지 않은 고객(과 그 기기·별칭)을 지운다. 공구 삭제·댓글 주문 다시 반영 뒤에 부른다 (개인정보 파기) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteWithoutOrders(Long sellerId) {
        return repository.deleteWithoutOrders(sellerId);
    }
}
