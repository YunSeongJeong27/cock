package com.depositcock.groupbuy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 판매 수량 더하기. 재고 한도를 넘어도 막지 않는다 (2026-10-16 사용자 결정: 로그인 없이 주문하므로 가짜 주문으로
     * 재고를 채워 진짜 손님을 막는 악용이 쉬움 → 막지 않고 판매자에게 남은 수량·초과 수량과 알림을 보여 준다).
     * 여러 사람이 동시에 주문해도 DB 가 한 줄씩 더하므로 수량이 빠지지 않는다.
     * 메모리의 Product.sold 는 바뀌지 않으므로 필요하면 EntityManager.refresh 로 다시 읽는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold + :quantity where p.id = :productId")
    int increaseSold(@Param("productId") Long productId, @Param("quantity") int quantity);

    /** 취소 시 판매 수량 복구 */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold - :quantity where p.id = :productId and p.sold >= :quantity")
    int decreaseSold(@Param("productId") Long productId, @Param("quantity") int quantity);

}
