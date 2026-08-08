package io.github.yromko.minesplat.voxel;

/** Resolution limits shared with the TripoSplat voxelization API. */
public final class VoxelResolutions {
    public static final int SERVER_MINIMUM = 2;
    public static final int SERVER_MAXIMUM = 1024;

    private VoxelResolutions() {
    }

    public static boolean isSupportedByServer(int resolution) {
        return resolution >= SERVER_MINIMUM
                && resolution <= SERVER_MAXIMUM
                && (resolution & (resolution - 1)) == 0;
    }
}
