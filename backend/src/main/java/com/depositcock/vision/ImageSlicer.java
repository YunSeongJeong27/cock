package com.depositcock.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 비전 API 로 보내기 전에 이미지를 다듬는다.
 * <p>
 * 1) 세로로 아주 긴 캡처(갤럭시 스크롤 캡처 등)는 위아래로 겹치게 잘라서 여러 조각으로 만든다.
 * 이유: 비전 모델은 긴 변 기준 최대 해상도가 정해져 있어(문서상 최신 모델 2576px) 아주 긴 이미지는 축소되며 글자가 뭉개질 수 있다.
 * 겹치는 부분이 있어서 경계에서 잘린 줄도 어느 한 조각에는 온전히 들어간다. 겹쳐서 두 번 읽힌 줄은 OverlapDeduplicator 로 합친다.
 * 정확도가 실제로 얼마나 좋아지는지는 실제 캡처로 확인하지 못했다 [판단].
 * <p>
 * 2) Claude API 이미지 제한(문서 확인 2026-10-13: 한 변 최대 8000px, 한 장 base64 10MB)을 넘는 원본·조각은 줄여서 다시 만든다.
 * 올리기 제한(10MB)이 API 제한(base64 10MB ≈ 원본 7.5MB)보다 커서, 줄이지 않으면 7.5MB 넘는 캡처는 API 가 거절한다.
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
    /**
     * 한 번에 메모리에 푸는 최대 픽셀 수. 넘으면 1/n 로 솎아서 읽는다 (솎아 읽으면 결과 크기만큼만 메모리를 씀).
     * 이만큼 긴 캡처는 조각 수 상한 때문에 조각이 길어져 모델도 어차피 줄여서 보므로, 솎아도 모델이 보는 해상도는 거의 같다 [판단].
     */
    static final long MAX_PIXELS = 20_000_000L;
    /** 이보다 픽셀이 많으면 받지 않는다 (작은 파일이 거대한 그림으로 풀리는 경우 등, 읽는 데 시간이 너무 걸림) */
    static final long MAX_DECODE_PIXELS = 150_000_000L;
    /** API 제한: 한 변 최대 8000px */
    static final int MAX_SIDE = 8000;
    /** API 제한: base64 10MB → 원본 약 7.5MB. "10MB" 를 10,000,000 바이트로 보고 여유를 더 둔다 */
    static final int MAX_BYTES = 7_000_000;
    /** 줄여서 다시 만들 때 긴 변. 문서상 고해상도 모델의 최대 긴 변(2576px)이라 모델이 보는 것은 그대로다 */
    static final int FIT_LONG_EDGE = 2576;
    /** 용량을 맞추려고 줄일 때 긴 변 하한 (이보다 작으면 글자를 못 읽으니 포기) */
    static final int MIN_LONG_EDGE = 1000;

    public record Piece(byte[] data, String mediaType) {
    }

    /** API 로 보낼 수 없는 이미지 (너무 크거나 줄이지 못함). 메시지는 화면에 그대로 보여 준다 */
    public static class UnusableImageException extends RuntimeException {
        public UnusableImageException(String message) {
            super(message);
        }
    }

    /** 헤더에서 읽은 크기와 실제 형식 (확장자·Content-Type 이 실제 형식과 다를 수 있어서 실제 형식을 쓴다) */
    record Header(int width, int height, String mediaType) {
        long pixels() {
            return (long) width * height;
        }
    }

    private ImageSlicer() {
    }

    /**
     * 받지 않을 이미지면 이유를, 아니면 null. 헤더만 읽어서 판단한다.
     * 사용량(하루 장수)을 잡기 전에 불러서, 보내지도 못할 이미지가 장수를 쓰지 않게 한다.
     */
    public static String rejectReason(byte[] data) {
        return rejectReason(header(data), data.length);
    }

    private static String rejectReason(Header header, int size) {
        if (header == null) {
            // webp 등 서버에서 열 수 없는 형식은 줄일 수 없으므로 용량만 본다 (크기는 API 가 판단)
            return size > MAX_BYTES ? "용량이 너무 큽니다. 7MB 이하로 줄이거나 png·jpg로 저장해서 올려 주세요." : null;
        }
        if (header.width() <= 0 || header.height() <= 0) {
            return "이미지 크기를 읽을 수 없습니다.";
        }
        if (header.pixels() > MAX_DECODE_PIXELS) {
            return "이미지가 너무 큽니다 (" + header.width() + "×" + header.height() + "). 나눠서 캡처해 올려 주세요.";
        }
        return null;
    }

    /**
     * API 로 보낼 조각들. 자를 필요가 없고 제한 안이면 원본 1개를 그대로 돌려준다.
     *
     * @throws UnusableImageException 받지 않을 이미지이거나 제한 안으로 줄이지 못했을 때
     */
    public static List<Piece> prepare(byte[] data, String mediaType) {
        Header header = header(data);
        String reason = rejectReason(header, data.length);
        if (reason != null) {
            throw new UnusableImageException(reason);
        }
        if (header == null) {
            return List.of(new Piece(data, mediaType));
        }
        boolean longCapture = header.height() > header.width() * SLICE_RATIO;
        boolean withinLimits = header.width() <= MAX_SIDE && header.height() <= MAX_SIDE && data.length <= MAX_BYTES;
        if (!longCapture && withinLimits) {
            return List.of(new Piece(data, header.mediaType()));
        }
        int subsample = header.pixels() > MAX_PIXELS
                ? (int) Math.ceil(Math.sqrt((double) header.pixels() / MAX_PIXELS)) : 1;
        BufferedImage image = decode(data, subsample);
        if (image == null) {
            if (withinLimits) {
                return List.of(new Piece(data, header.mediaType()));
            }
            throw new UnusableImageException("이미지를 줄이지 못했습니다. 줄여서 다시 올려 주세요.");
        }
        List<Piece> result = new ArrayList<>();
        try {
            for (BufferedImage part : longCapture ? cut(image) : List.of(image)) {
                result.add(encode(part));
            }
        } catch (UnusableImageException e) {
            throw e;
        } catch (RuntimeException e) {
            // 그림 처리 중 예상 못 한 오류: 제한 안이면 원본을 그대로 보내고, 아니면 이 장만 실패로
            if (withinLimits) {
                return List.of(new Piece(data, header.mediaType()));
            }
            throw new UnusableImageException("이미지를 줄이지 못했습니다. 줄여서 다시 올려 주세요.");
        }
        return result;
    }

    /** 위아래로 겹치게 자른다. 조각이 MAX_PIECES 를 넘으면 조각을 길게 해서 맞춘다 */
    private static List<BufferedImage> cut(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int overlap = (int) Math.round(width * OVERLAP_RATIO);
        int pieceHeight = (int) Math.round(width * PIECE_RATIO);
        int pieces = (int) Math.ceil((double) (height - overlap) / (pieceHeight - overlap));
        if (pieces > MAX_PIECES) {
            pieceHeight = (int) Math.ceil((double) (height - overlap) / MAX_PIECES) + overlap;
            pieces = MAX_PIECES;
        }
        int step = pieceHeight - overlap;
        List<BufferedImage> result = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            int top = i * step;
            if (top >= height) {
                break;
            }
            int h = Math.min(pieceHeight, height - top);
            result.add(image.getSubimage(0, top, width, h));
            if (top + h >= height) {
                break;
            }
        }
        return result;
    }

    /**
     * API 제한 안의 이미지로 만든다. png 를 먼저 쓰고(글자가 깨끗함), 용량이 넘으면 긴 변 2576px 로 줄인 png,
     * 그래도 넘으면 jpeg 로 긴 변을 조금씩 줄여 가며 맞춘다 (글자 캡처는 보통 png 단계에서 끝난다 [판단]).
     */
    private static Piece encode(BufferedImage image) {
        BufferedImage rgb = Math.max(image.getWidth(), image.getHeight()) > MAX_SIDE
                ? shrink(image, FIT_LONG_EDGE) : toRgb(image);
        byte[] png = write(rgb);
        if (fits(png)) {
            return new Piece(png, "image/png");
        }
        BufferedImage smaller = shrink(rgb, FIT_LONG_EDGE);
        if (smaller != rgb) {
            png = write(smaller);
            if (fits(png)) {
                return new Piece(png, "image/png");
            }
        }
        for (int longEdge = Math.max(smaller.getWidth(), smaller.getHeight()); longEdge >= MIN_LONG_EDGE;
             longEdge = longEdge * 3 / 4) {
            byte[] jpeg = writeJpeg(shrink(smaller, longEdge), 0.9f);
            if (fits(jpeg)) {
                return new Piece(jpeg, "image/jpeg");
            }
        }
        throw new UnusableImageException("이미지를 줄이지 못했습니다. 줄여서 다시 올려 주세요.");
    }

    private static boolean fits(byte[] encoded) {
        return encoded != null && encoded.length <= MAX_BYTES;
    }

    /** 긴 변이 longEdge 를 넘으면 비율을 지켜 줄인다. 한 번에 많이 줄이면 글자가 깨지므로 반씩 나눠 줄인다 */
    static BufferedImage shrink(BufferedImage image, int longEdge) {
        double scale = (double) longEdge / Math.max(image.getWidth(), image.getHeight());
        if (scale >= 1) {
            return image;
        }
        int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage current = image;
        while (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
            int w = Math.max(targetWidth, current.getWidth() / 2);
            int h = Math.max(targetHeight, current.getHeight() / 2);
            current = draw(current, w, h);
        }
        return current;
    }

    /** 투명한 부분은 흰 바탕으로 채운 RGB 복사본 (조각을 원본과 떼어 놓고, jpeg 로도 저장할 수 있게) */
    private static BufferedImage toRgb(BufferedImage image) {
        return draw(image, image.getWidth(), image.getHeight());
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** 헤더만 읽어 크기와 실제 형식. 서버에서 열 수 없거나 Claude 가 받는 형식(png·jpeg·gif)이 아니면 null */
    static Header header(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                String mediaType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    default -> null;
                };
                if (mediaType == null) {
                    return null;
                }
                reader.setInput(in, true, true);
                return new Header(reader.getWidth(0), reader.getHeight(0), mediaType);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** subsample > 1 이면 가로·세로 1/subsample 로 솎아서 읽는다. 읽지 못하면 null */
    private static BufferedImage decode(byte[] data, int subsample) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] write(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ImageIO.write(image, "png", out) ? out.toByteArray() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
