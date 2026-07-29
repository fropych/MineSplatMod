package io.github.yromko.minesplat.util;

import io.github.yromko.minesplat.api.TripoSplatApiClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImageFiles {
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    private ImageFiles() {
    }

    public static void validate(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Select a PNG or JPEG image");
        }
        long size = Files.size(path);
        if (size <= 0 || size > TripoSplatApiClient.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image must be no larger than 25 MiB");
        }
        byte[] prefix;
        try (var input = Files.newInputStream(path)) {
            prefix = input.readNBytes(8);
        }
        boolean png = prefix.length == 8;
        for (int i = 0; png && i < PNG.length; i++) {
            png = prefix[i] == PNG[i];
        }
        boolean jpeg = prefix.length >= 3
                && Byte.toUnsignedInt(prefix[0]) == 0xff
                && Byte.toUnsignedInt(prefix[1]) == 0xd8
                && Byte.toUnsignedInt(prefix[2]) == 0xff;
        if (!png && !jpeg) {
            throw new IllegalArgumentException("Only PNG and JPEG images are supported");
        }
    }
}
