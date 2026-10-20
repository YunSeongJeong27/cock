package com.depositcock.deposit;

import java.util.List;

import lombok.Builder;

/**
 * 거래내역 파일을 읽은 결과.
 *
 * @param mapping        실제로 쓴 열 지정 (자동 탐지 또는 판매자 지정)
 * @param autoDetected   열을 자동으로 찾았는지
 * @param headerRowNo    헤더 행 번호 (1부터). 못 찾았으면 0
 * @param headers        헤더 행의 열 이름들
 * @param columnOptions  판매자가 열을 직접 고를 때 보여줄 후보 (위쪽 30줄의 글자 칸 값)
 * @param preview        위쪽 몇 줄의 원본 (열을 못 찾았을 때 화면에 보여준다)
 * @param deposits       입금 줄
 * @param skippedRows    건너뛴 줄 수 (출금, 0원 이하, 이름 없음, 이자)
 * @param message        안내 문구 (열을 못 찾았을 때 등)
 */
@Builder(toBuilder = true)
public record ParsedStatement(
        ColumnMapping mapping,
        boolean autoDetected,
        int headerRowNo,
        List<String> headers,
        List<String> columnOptions,
        List<List<String>> preview,
        List<DepositLine> deposits,
        int skippedRows,
        String message) {

    public boolean ok() {
        return headerRowNo > 0;
    }
}
