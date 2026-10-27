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
 * @param skippedRows    건너뛴 줄 수 (0원 이하·출금 금액, 이름 없음, 거래 구분으로 뺀 줄 포함)
 * @param excludedRows   그중 거래 구분 값(캐시백·카드·이자 등)으로 뺀 줄 수 (2026-10-27)
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
        int excludedRows,
        String message,
        /** 거래내역서 위쪽에서 찾은 계좌번호 (가려진 글자 포함, 못 찾으면 null, 2026-10-27) */
        String accountNumber,
        /** 지정한 헤더(컬럼)명 중 파일에서 못 찾은 것 (찾았으면 빈 목록) */
        List<String> missingColumns) {

    public boolean ok() {
        return headerRowNo > 0;
    }
}
