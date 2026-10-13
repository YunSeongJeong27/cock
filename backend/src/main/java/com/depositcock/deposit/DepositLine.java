package com.depositcock.deposit;

import java.time.LocalDateTime;

/**
 * 거래내역에서 읽은 입금 1건.
 *
 * @param rowNo       파일에서의 행 번호 (1부터, 화면 표시용)
 * @param depositedAt 변환한 입금 시각. 형식을 모르면 null (원문은 dateText 에 그대로 둔다)
 */
public record DepositLine(int rowNo, String name, int amount, String dateText, LocalDateTime depositedAt) {
}
