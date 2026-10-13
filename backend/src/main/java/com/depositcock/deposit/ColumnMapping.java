package com.depositcock.deposit;

/**
 * 거래내역 파일의 열 이름 지정. 이름·금액은 필수, 날짜·유형은 선택.
 * 비교할 때는 공백을 모두 지우고 정확히 같은지 본다.
 */
public record ColumnMapping(String dateColumn, String nameColumn, String amountColumn, String typeColumn) {

    public boolean isComplete() {
        return notBlank(nameColumn) && notBlank(amountColumn);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
