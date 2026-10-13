package com.depositcock.groupbuy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 재고 원자 처리 (정리본 4-4장). 영향받은 행이 0 이면 품절.
     * 여러 사람이 동시에 주문해도 DB 가 한 줄씩 처리하므로 재고를 넘지 않는다.
 * 메모리의 Product.sold 는 바뀌지 않으므로 필요하면 EntityManager.refresh 로 다시 읽는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold + :quantity "
            + "where p.id = :productId and (p.stockLimit is null or p.sold + :quantity <= p.stockLimit)")
    int increaseSold(@Param("productId") Long productId, @Param("quantity") int quantity);

    /** 취소 시 판매 수량 복구 */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold - :quantity where p.id = :productId and p.sold >= :quantity")
    int decreaseSold(@Param("productId") Long productId, @Param("quantity") int quantity);

    /** 댓글 주문 반영용: 이미 받은 댓글 주문이라 재고 한도와 상관없이 판매 수량에 더한다. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold + :quantity where p.id = :productId")
    int addSoldUnchecked(@Param("productId") Long productId, @Param("quantity") int quantity);
}
