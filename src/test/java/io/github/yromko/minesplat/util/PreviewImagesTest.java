package io.github.yromko.minesplat.util;

import net.minecraft.client.texture.NativeImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewImagesTest {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    @TempDir
    Path temporary;

    @Test
    void convertsJpegToPngForMinecraftPreview() throws Exception {
        Path jpeg = temporary.resolve("пример изображения.jpg");
        BufferedImage source = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        assertTrue(ImageIO.write(source, "jpeg", jpeg.toFile()));

        byte[] normalized = PreviewImages.normalizedPng(jpeg, 1024);
        assertArrayEquals(PNG_SIGNATURE, java.util.Arrays.copyOf(normalized, 8));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertEquals(80, decoded.getWidth());
        assertEquals(40, decoded.getHeight());
    }

    @Test
    void boundsLargePreviewTextures() throws Exception {
        Path png = temporary.resolve("large.png");
        BufferedImage source = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
        assertTrue(ImageIO.write(source, "png", png.toFile()));

        byte[] normalized = PreviewImages.normalizedPng(png, 256);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertEquals(256, decoded.getWidth());
        assertEquals(128, decoded.getHeight());
    }

    @Test
    void loadsPreviewLargerThanTheLwjglMemoryStack() throws Exception {
        Path png = temporary.resolve("incompressible.png");
        BufferedImage source = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        SplittableRandom random = new SplittableRandom(42);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, 0xff000000 | random.nextInt(0x01000000));
            }
        }
        assertTrue(ImageIO.write(source, "png", png.toFile()));
        assertTrue(PreviewImages.normalizedPng(png, 1024).length > 64 * 1024);

        try (NativeImage preview = PreviewImages.load(png, 1024)) {
            assertEquals(512, preview.getWidth());
            assertEquals(512, preview.getHeight());
        }
    }
}
