package io.github.yromko.minesplat.util;

import net.minecraft.client.texture.NativeImage;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decodes supported input images and produces a bounded PNG for Minecraft textures. */
public final class PreviewImages {
    private PreviewImages() {
    }

    /**
     * Loads a bounded preview without copying the encoded image into LWJGL's fixed-size
     * {@code MemoryStack}. Minecraft's {@code NativeImage.read(byte[])} overload performs that
     * stack copy, while the stream overload uses a separately allocated native buffer.
     */
    public static NativeImage load(Path path, int maximumDimension) throws IOException {
        byte[] png = normalizedPng(path, maximumDimension);
        try (InputStream input = new ByteArrayInputStream(png)) {
            return NativeImage.read(input);
        }
    }

    static byte[] normalizedPng(Path path, int maximumDimension) throws IOException {
        if (maximumDimension <= 0) {
            throw new IllegalArgumentException("Preview size must be positive");
        }
        ImageFiles.validate(path);

        BufferedImage source;
        try (InputStream input = Files.newInputStream(path)) {
            source = ImageIO.read(input);
        }
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IOException("Cannot decode the selected PNG or JPEG image");
        }

        double scale = Math.min(1.0, maximumDimension
                / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = preview.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(preview, "png", output)) {
            throw new IOException("PNG preview encoder is unavailable");
        }
        return output.toByteArray();
    }
}
