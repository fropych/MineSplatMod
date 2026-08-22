package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.workflow.GenerationSnapshot;
import io.github.yromko.minesplat.workflow.GenerationState;

final class MineSplatUiFlow {
    private MineSplatUiFlow() {
    }

    static Page initialPage(GenerationSnapshot snapshot) {
        if (snapshot.state() == GenerationState.SUCCEEDED) {
            return Page.RESULT;
        }
        if (snapshot.state().active()
                || snapshot.pollingPaused()
                || snapshot.state() == GenerationState.FAILED) {
            return Page.PROGRESS;
        }
        return Page.SOURCE;
    }

    static ProgressStage progressStage(GenerationState state) {
        return switch (state) {
            case UPLOADING -> ProgressStage.PREPARING;
            case GENERATION_QUEUED, GENERATION_RUNNING -> ProgressStage.GENERATING;
            case VOXELIZATION_QUEUED, VOXELIZATION_RUNNING -> ProgressStage.VOXELIZING;
            case DOWNLOADING, CONVERTING -> ProgressStage.CONVERTING;
            case SAVING, PLACING, SUCCEEDED -> ProgressStage.SAVING;
            default -> null;
        };
    }

    enum Page {
        SOURCE,
        BLOCKS,
        PROGRESS,
        RESULT
    }

    enum ProgressStage {
        PREPARING("preparing"),
        GENERATING("generating"),
        VOXELIZING("voxelizing"),
        CONVERTING("converting"),
        SAVING("saving");

        private final String id;

        ProgressStage(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}
