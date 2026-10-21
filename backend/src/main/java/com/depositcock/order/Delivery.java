package com.depositcock.order;

/**
 * 주문의 배송 정보. 배송이 아닌 주문은 NONE (모든 칸 null).
 * 고객 주문서와 판매자 주문 변경이 같은 규칙(OrderRules.delivery)으로 만든다.
 */
record Delivery(String recipientName, String recipientPhone, String zipcode, String address, String addressDetail) {

    static final Delivery NONE = new Delivery(null, null, null, null, null);

    static Delivery of(CustomerOrder order) {
        return new Delivery(order.getRecipientName(), order.getRecipientPhone(), order.getZipcode(), order.getAddress(),
                order.getAddressDetail());
    }
}
