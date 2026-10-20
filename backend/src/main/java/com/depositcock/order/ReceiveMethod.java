package com.depositcock.order;

import lombok.RequiredArgsConstructor;

/** 수령 방법. label 은 엑셀·변경 기록에 쓰는 이름 */
@RequiredArgsConstructor
public enum ReceiveMethod {
    PICKUP("픽업"),
    DELIVERY("배송");

    private final String label;

    public String label() {
        return label;
    }
}
