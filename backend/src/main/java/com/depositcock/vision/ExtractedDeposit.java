package com.depositcock.vision;

/**
 * 은행앱 캡처에서 읽은 거래 1건. JSON 스키마(정리본 7장):
 * {"deposits":[{"name":"김지영","amount":3800,"datetime":"10-08 10:01","type":"입금","order":0}]}
 * 출금도 type="출금"으로 읽고, 제외는 우리 코드에서 한다.
 */
public record ExtractedDeposit(String name, long amount, String datetime, String type, int order) {
}
