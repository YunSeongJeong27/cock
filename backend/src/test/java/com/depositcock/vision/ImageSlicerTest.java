package com.depositcock.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSlicerTest {

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void normalScreenshotIsNotSliced() throws Exception {
        byte[] data = png(100, 200);
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(data, "image/png");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).data()).isSameAs(data);
    }

    @Test
    void longCaptureIsSlicedWithOverlap() throws Exception {
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(png(100, 700), "image/png");
        // 조각 높이 200, 겹침 40, 간격 160 → 0,160,320,480(끝 680>700? 480+200=680) ,640 → 5조각
        assertThat(pieces).hasSizeBetween(4, 6);
        int covered = 0;
        for (ImageSlicer.Piece p : pieces) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(p.data()));
            assertThat(img.getWidth()).isEqualTo(100);
            assertThat(img.getHeight()).isLessThanOrEqualTo(200);
            covered += img.getHeight();
        }
        assertThat(covered).isGreaterThan(700); // 겹침 때문에 원본보다 길다
    }

    @Test
    void veryLongCaptureIsCappedAtMaxPieces() throws Exception {
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(png(100, 3000), "image/png");
        assertThat(pieces).hasSize(ImageSlicer.MAX_PIECES);
        int last = 0;
        for (ImageSlicer.Piece p : pieces) {
            last = ImageIO.read(new ByteArrayInputStream(p.data())).getHeight();
        }
        assertThat(last).isPositive();
    }

    @Test
    void undecodableFormatIsPassedThrough() {
        byte[] data = {1, 2, 3};
        assertThat(ImageSlicer.prepare(data, "image/webp")).hasSize(1);
        assertThat(ImageSlicer.prepare(data, "image/png")).hasSize(1); // 깨진 png 도 원본 그대로
    }

    private static BufferedImage decoded(ImageSlicer.Piece piece) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(piece.data()));
    }

    /** API 제한(한 변 8000px)을 넘으면 긴 변 2576px 로 줄여서 보낸다 */
    @Test
    void imageWiderThanApiLimitIsShrunk() throws Exception {
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(png(9000, 300), "image/png");
        assertThat(pieces).hasSize(1);
        BufferedImage img = decoded(pieces.get(0));
        assertThat(img.getWidth()).isEqualTo(ImageSlicer.FIT_LONG_EDGE);
        assertThat(img.getHeight()).isEqualTo((int) Math.round(300 * (double) ImageSlicer.FIT_LONG_EDGE / 9000));
        assertThat(pieces.get(0).mediaType()).isEqualTo("image/png");
    }

    /** 용량이 API 제한(base64 10MB ≈ 7MB)을 넘는 png 는 png 로 다시 만들고, 그래도 크면 jpeg 로 보낸다 */
    @Test
    void imageOverByteLimitIsReencoded() throws Exception {
        BufferedImage noise = new BufferedImage(2400, 1700, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", out);
        byte[] data = out.toByteArray();
        assertThat(data.length).isGreaterThan(ImageSlicer.MAX_BYTES);

        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(data, "image/png");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).data().length).isLessThanOrEqualTo(ImageSlicer.MAX_BYTES);
        assertThat(decoded(pieces.get(0)).getWidth()).isLessThanOrEqualTo(ImageSlicer.FIT_LONG_EDGE);
    }

    /** 서버에서 열 수 없는 형식(webp 등)은 줄일 수 없으므로, 용량이 넘으면 보내기 전에 거절한다 */
    @Test
    void undecodableOverByteLimitIsRejected() {
        byte[] data = new byte[ImageSlicer.MAX_BYTES + 1];
        assertThat(ImageSlicer.rejectReason(data)).contains("용량");
        assertThatThrownBy(() -> ImageSlicer.prepare(data, "image/webp"))
                .isInstanceOf(ImageSlicer.UnusableImageException.class);
    }

    /** 픽셀이 지나치게 많은 그림(작은 파일이 거대한 그림으로 풀리는 경우 등)은 헤더만 보고 거절한다 */
    @Test
    void hugePixelCountIsRejectedFromHeader() throws Exception {
        byte[] data = withSize(png(10, 10), 20_000, 10_000);
        assertThat(ImageSlicer.header(data)).isNotNull();
        assertThat(ImageSlicer.rejectReason(data)).contains("20000×10000");
    }

    /** 픽셀이 MAX_PIXELS 를 넘는 긴 캡처는 솎아 읽어서(메모리 보호) 자른다 */
    @Test
    void veryLargeLongCaptureIsSubsampledThenSliced() throws Exception {
        BufferedImage img = new BufferedImage(1000, 25_000, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(out.toByteArray(), "image/png");
        assertThat(pieces).hasSize(ImageSlicer.MAX_PIECES);
        for (ImageSlicer.Piece p : pieces) {
            BufferedImage piece = decoded(p);
            assertThat(piece.getWidth()).isEqualTo(500); // 25M 픽셀 > 20M → 1/2 로 솎음
            assertThat(piece.getHeight()).isLessThanOrEqualTo(ImageSlicer.MAX_SIDE);
        }
    }

    /** 확장자·Content-Type 이 실제 형식과 달라도 실제 형식으로 보낸다 */
    @Test
    void actualFormatIsUsedAsMediaType() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(out.toByteArray(), "image/png");
        assertThat(pieces.get(0).mediaType()).isEqualTo("image/jpeg");
    }

    /** png 의 IHDR(가로·세로)만 바꾼다 (헤더 검사용. 실제 그림 데이터와는 맞지 않음) */
    private static byte[] withSize(byte[] png, int width, int height) {
        byte[] copy = png.clone();
        ByteBuffer buf = ByteBuffer.wrap(copy);
        // 8바이트 서명 + 4바이트 길이 + "IHDR" 다음이 가로·세로
        buf.putInt(16, width);
        buf.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(copy, 12, 4 + 13);
        buf.putInt(12 + 4 + 13, (int) crc.getValue());
        return copy;
    }
}
