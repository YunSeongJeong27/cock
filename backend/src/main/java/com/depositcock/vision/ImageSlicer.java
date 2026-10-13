package com.depositcock.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * 세로로 아주 긴 캡처(갤럭시 스크롤 캡처 등)를 위아래로 겹치게 잘라서 여러 조각으로 만든다.
 * 이유: 비전 모델은 긴 변 기준 최대 해상도가 정해져 있어(문서상 최신 모델 2576px) 아주 긴 이미지는 축소되며 글자가 뭉개질 수 있다.
 * 겹치는 부분이 있어서 경계에서 잘린 줄도 어느 한 조각에는 온전히 들어간다. 겹쳐서 두 번 읽힌 줄은 OverlapDeduplicator 로 합친다.
 * 정확도가 실제로 얼마나 좋아지는지는 실제 캡처로 확인하지 못했다 [판단].
 */
public final class ImageSlicer {

    /** 세로/가로 비율이 이보다 크면 자른다 */
    static final double SLICE_RATIO = 2.5;
    /** 조각 높이 = 가로 × 2 (기본) */
    static final double PIECE_RATIO = 2.0;
    /** 겹침 = 가로 × 0.4 (은행앱·댓글 한 줄 높이보다 충분히 크게) */
    static final double OVERLAP_RATIO = 0.4;
    /** 한 장을 최대 몇 조각까지 (비용 상한) */
    static final int MAX_PIECES = 6;
    /** 이보다 큰 이미지는 메모리 보호를 위해 자르지 않는다 (픽셀 수) */
    static final long MAX_PIXELS = 40_000_000L;

    private static final Set<String> DECODABLE = Set.of("image/png", "image/jpeg", "image/gif");

    public record Piece(byte[] data, String mediaType) {
    }

    private ImageSlicer() {
    }

    /** 자를 필요가 없거나 읽을 수 없는 형식(webp 등)이면 원본 1개를 그대로 돌려준다. */
    public static List<Piece> slice(byte[] data, String mediaType) {
        List<Piece> original = List.of(new Piece(data, mediaType));
        if (!DECODABLE.contains(mediaType)) {
            return original;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return original;
        }
        if (image == null) {
            return original;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= width * SLICE_RATIO || (long) width * height > MAX_PIXELS) {
            return original;
        }
        int overlap = (int) Math.round(width * OVERLAP_RATIO);
        int pieceHeight = (int) Math.round(width * PIECE_RATIO);
        // 조각이 너무 많아지면 조각을 길게 해서 MAX_PIECES 안에 맞춘다
        int pieces = (int) Math.ceil((double) (height - overlap) / (pieceHeight - overlap));
        if (pieces > MAX_PIECES) {
            pieceHeight = (int) Math.ceil((double) (height - overlap) / MAX_PIECES) + overlap;
            pieces = MAX_PIECES;
        }
        int step = pieceHeight - overlap;
        List<Piece> result = new ArrayList<>();
        try {
            for (int i = 0; i < pieces; i++) {
                int top = i * step;
                if (top >= height) {
                    break;
                }
                int h = Math.min(pieceHeight, height - top);
                BufferedImage sub = image.getSubimage(0, top, width, h);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(toRgb(sub), "png", out);
                result.add(new Piece(out.toByteArray(), "image/png"));
                if (top + h >= height) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            return original;
        }
        return result.isEmpty() ? original : result;
    }

    private static BufferedImage toRgb(BufferedImage sub) {
        BufferedImage copy = new BufferedImage(sub.getWidth(), sub.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(sub, 0, 0, null);
        return copy;
    }
}
