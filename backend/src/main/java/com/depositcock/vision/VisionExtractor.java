package com.depositcock.vision;

import java.util.List;

/**
 * 스크린샷 글자 읽기 (비전 AI). 공급자는 바꿀 수 있게 인터페이스로 둔다 (정리본 7장, 개발 전 인터뷰 결과 5번).
 * 정확도와 장당 비용은 실제 캡처로 아직 검증하지 않았다.
 */
public interface VisionExtractor {

    /** 공급자 이름 (화면 표시용) */
    String provider();

    /** 실제로 이미지를 읽을 수 있는 상태인지 (키가 설정되었는지 등) */
    boolean available();

    List<ExtractedDeposit> extractDeposits(byte[] image, String mediaType);

    List<ExtractedComment> extractComments(byte[] image, String mediaType);

    /**
     * 긴 캡처를 자른 조각을 읽는다. 조각 경계에서 잘린 줄은 빼도록 알려줄 수 있는 구현은 이 메서드를 다시 정의한다.
     */
    default List<ExtractedDeposit> extractDepositsFromPiece(byte[] image, String mediaType) {
        return extractDeposits(image, mediaType);
    }

    default List<ExtractedComment> extractCommentsFromPiece(byte[] image, String mediaType) {
        return extractComments(image, mediaType);
    }
}
