package com.depositcock.deposit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 거래내역 파일 종류 (2026-10-27 사용자 결정). 같은 은행도 PDF 와 엑셀의 헤더(컬럼)명이 달라서
 * 은행 헤더(컬럼) 설정을 파일 종류마다 따로 둔다. CSV 는 엑셀과 같이 본다
 */
@Getter
@RequiredArgsConstructor
public enum StatementType {
    PDF("PDF"),
    EXCEL("엑셀·CSV");

    /** 화면·안내 문구에 보이는 이름 */
    private final String label;

    /** 올린 파일의 종류 (이름이 .pdf 이거나 내용이 PDF 면 PDF, 나머지는 엑셀·CSV) */
    public static StatementType of(String fileName, byte[] content) {
        return BankStatementReader.isPdf(fileName, content) ? PDF : EXCEL;
    }
}
