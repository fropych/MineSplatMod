package io.github.yromko.minesplat.workflow;

public enum GenerationState {
    IDLE,
    UPLOADING,
    GENERATION_QUEUED,
    GENERATION_RUNNING,
    VOXELIZATION_QUEUED,
    VOXELIZATION_RUNNING,
    DOWNLOADING,
    CONVERTING,
    SAVING,
    PLACING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean active() {
        return switch (this) {
            case UPLOADING, GENERATION_QUEUED, GENERATION_RUNNING,
                    VOXELIZATION_QUEUED, VOXELIZATION_RUNNING,
                    DOWNLOADING, CONVERTING, SAVING, PLACING -> true;
            default -> false;
        };
    }
}
