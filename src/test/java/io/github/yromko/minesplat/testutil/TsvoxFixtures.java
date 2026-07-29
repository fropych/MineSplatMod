package io.github.yromko.minesplat.testutil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TsvoxFixtures {
    private TsvoxFixtures() {
    }

    public static byte[] valid(int resolution, int[] indices, byte[] colors) {
        if (colors.length != indices.length * 3) {
            throw new IllegalArgumentException("Each occupied index needs one RGB record");
        }
        long voxelCount = (long) resolution * resolution * resolution;
        int occupancyBytes = Math.toIntExact((voxelCount + 7) / 8);
        byte[] occupancy = new byte[occupancyBytes];
        for (int index : indices) {
            occupancy[index >>> 3] |= (byte) (1 << (index & 7));
        }
        int payloadBytes = occupancy.length + colors.length;
        ByteBuffer output = ByteBuffer.allocate(128 + payloadBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.put(new byte[]{'T', 'S', 'V', 'O', 'X', 'E', 'L', 0});
        output.putInt(2);
        output.putInt(128);
        output.putInt(resolution);
        output.putInt(0);
        output.putInt(2);
        output.putInt(3);
        output.putLong(indices.length);
        output.putLong(indices.length);
        output.putFloat(-1).putFloat(-1).putFloat(-1);
        output.putFloat(0.1f);
        output.putFloat(11.345f);
        output.putFloat(0.1f);
        output.putFloat(0.125f);
        output.putFloat(0.625f);
        output.putInt(10);
        output.putInt(0x0f);
        output.putLong(32768);
        output.putLong(payloadBytes);
        output.putLong(occupancy.length);
        output.putLong(colors.length);
        output.putLong(0);
        output.put(occupancy);
        output.put(colors);
        return output.array();
    }

    public static byte[] oneWhiteVoxel() {
        return valid(32, new int[]{0}, new byte[]{(byte) 255, (byte) 255, (byte) 255});
    }
}
