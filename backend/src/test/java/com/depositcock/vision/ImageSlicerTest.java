package com.depositcock.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        List<ImageSlicer.Piece> pieces = ImageSlicer.slice(data, "image/png");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).data()).isSameAs(data);
    }

    @Test
    void longCaptureIsSlicedWithOverlap() throws Exception {
        List<ImageSlicer.Piece> pieces = ImageSlicer.slice(png(100, 700), "image/png");
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
        List<ImageSlicer.Piece> pieces = ImageSlicer.slice(png(100, 3000), "image/png");
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
        assertThat(ImageSlicer.slice(data, "image/webp")).hasSize(1);
        assertThat(ImageSlicer.slice(data, "image/png")).hasSize(1); // 깨진 png 도 원본 그대로
    }
}
