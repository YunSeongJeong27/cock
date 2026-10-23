package com.depositcock.order;

/**
 * 공구 하나의 주문 집계 (공구 목록 요약용, CustomerOrderRepository.totalsBySeller). 숫자 뜻은 GroupBuyService.dashboard 와 같다.
 * 상품 수량 합계는 주문 상품 줄을 같이 묶으면 주문이 줄 수만큼 늘어나서 따로 센다 (quantitiesBySeller)
 */
public record OrderTotals(
        Long groupBuyId,
        Long orderCount,
        Long totalAmount,
        Long prepaidCount,
        Long paidCount,
        Long onsiteCount,
        Long pickedUpCount,
        Long refundPendingCount,
        Long refundRequestCount,
        Long cancelledCount) {
}
