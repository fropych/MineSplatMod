package io.github.yromko.minesplat.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class TsvoxReader {
    private static final byte[] MAGIC = {'T', 'S', 'V', 'O', 'X', 'E', 'L', 0};
    private static final int HEADER_BYTES = 128;
    private static final int REQUIRED_FLAGS = 0x0f;

    public TsvoxGrid read(byte[] data, int expectedResolution) {
        if (data == null || data.length < HEADER_BYTES) {
            throw error("file is shorter than the 128-byte header");
        }
        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        input.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw error("magic is not TSVOXEL");
        }

        int version = input.getInt();
        int headerBytes = input.getInt();
        int resolution = input.getInt();
        int axisOrder = input.getInt();
        int colorType = input.getInt();
        int recordBytes = input.getInt();
        long occupiedCount = input.getLong();
        long recordCount = input.getLong();
        float[] origin = {input.getFloat(), input.getFloat(), input.getFloat()};
        float voxelSize = input.getFloat();
        float iso = input.getFloat();
        float opacityThreshold = input.getFloat();
        float tolerance = input.getFloat();
        float colorWeightPower = input.getFloat();
        int integrationSteps = input.getInt();
        int flags = input.getInt();
        long sourceGaussianCount = input.getLong();
        long payloadBytes = input.getLong();
        long occupancyBytes = input.getLong();
        long colorBytes = input.getLong();
        long reserved = input.getLong();

        if (version != 2 || headerBytes != HEADER_BYTES) {
            throw error("unsupported TSVOX version or header size");
        }
        if (resolution != expectedResolution || !supportedResolution(resolution)) {
            throw error("resolution does not match the requested 32, 64, or 128 grid");
        }
        if (axisOrder != 0 || colorType != 2 || recordBytes != 3) {
            throw error("unsupported axis or color record layout");
        }
        if ((flags & REQUIRED_FLAGS) != REQUIRED_FLAGS) {
            throw error("required little-endian/surface-shell flags are missing");
        }
        if (reserved != 0) {
            throw error("reserved header field is non-zero");
        }
        if (!positiveFinite(voxelSize)
                || !positiveFinite(iso)
                || !finiteInRange(opacityThreshold, 0, 1)
                || !positiveFinite(tolerance)
                || !positiveFinite(colorWeightPower)
                || integrationSteps < 1
                || sourceGaussianCount == 0) {
            throw error("header contains invalid numeric metadata");
        }
        for (float value : origin) {
            if (!Float.isFinite(value)) {
                throw error("origin contains a non-finite value");
            }
        }

        long voxelCount = (long) resolution * resolution * resolution;
        long expectedOccupancyBytes = (voxelCount + 7) / 8;
        if (occupancyBytes != expectedOccupancyBytes) {
            throw error("occupancy bitset has an invalid size");
        }
        if (occupiedCount != recordCount || occupiedCount < 1 || occupiedCount > voxelCount) {
            throw error("occupied and record counts are inconsistent");
        }
        if (colorBytes != occupiedCount * 3) {
            throw error("color payload does not match the occupied count");
        }
        if (payloadBytes != occupancyBytes + colorBytes
                || payloadBytes != data.length - HEADER_BYTES) {
            throw error("payload size does not match the file length");
        }
        if (occupiedCount > Integer.MAX_VALUE || colorBytes > Integer.MAX_VALUE) {
            throw error("payload is too large for the client");
        }

        byte[] occupancy = new byte[(int) occupancyBytes];
        input.get(occupancy);
        validateUnusedBits(occupancy, voxelCount);

        int actualCount = 0;
        for (byte value : occupancy) {
            actualCount += Integer.bitCount(Byte.toUnsignedInt(value));
        }
        if (actualCount != occupiedCount) {
            throw error("occupancy popcount does not match the header");
        }

        byte[] colors = new byte[(int) colorBytes];
        input.get(colors);
        if (input.hasRemaining()) {
            throw error("file contains bytes after the declared payload");
        }

        int[] indices = new int[actualCount];
        int cursor = 0;
        for (int linearIndex = 0; linearIndex < voxelCount; linearIndex++) {
            if ((occupancy[linearIndex >>> 3] & (1 << (linearIndex & 7))) != 0) {
                indices[cursor++] = linearIndex;
            }
        }
        return new TsvoxGrid(resolution, occupancy, indices, colors, origin, voxelSize);
    }

    private static void validateUnusedBits(byte[] occupancy, long voxelCount) {
        int usedBits = (int) (voxelCount & 7);
        if (usedBits == 0) {
            return;
        }
        int allowedMask = (1 << usedBits) - 1;
        if ((Byte.toUnsignedInt(occupancy[occupancy.length - 1]) & ~allowedMask) != 0) {
            throw error("unused high occupancy bits are non-zero");
        }
    }

    private static boolean supportedResolution(int value) {
        return value == 32 || value == 64 || value == 128;
    }

    private static boolean positiveFinite(float value) {
        return Float.isFinite(value) && value > 0;
    }

    private static boolean finiteInRange(float value, float minimum, float maximum) {
        return Float.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static TsvoxFormatException error(String message) {
        return new TsvoxFormatException("Invalid TSVOX v2: " + message);
    }
}
