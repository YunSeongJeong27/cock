package com.depositcock.order;

/** 수령 방법. label 은 엑셀·변경 기록에 쓰는 이름 */
public enum ReceiveMethod {
    PICKUP("픽업"),
    DELIVERY("배송");

    private final String label;

    ReceiveMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
