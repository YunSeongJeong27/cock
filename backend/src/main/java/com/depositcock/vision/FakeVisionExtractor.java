package com.depositcock.vision;

import java.util.List;

/**
 * 비전 AI 키가 없을 때 쓰는 구현. 아무것도 읽지 않는다 (판매자가 검수 화면에서 직접 입력).
 */
public class FakeVisionExtractor implements VisionExtractor {

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<ExtractedDeposit> extractDeposits(byte[] image, String mediaType) {
        return List.of();
    }

    @Override
    public List<ExtractedComment> extractComments(byte[] image, String mediaType) {
        return List.of();
    }
}
