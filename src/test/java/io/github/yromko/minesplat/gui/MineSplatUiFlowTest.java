package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.workflow.GenerationSnapshot;
import io.github.yromko.minesplat.workflow.GenerationState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.yromko.minesplat.gui.MineSplatUiFlow.Page.PROGRESS;
import static io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage.CONVERTING;
import static io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage.GENERATING;
import static io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage.PREPARING;
import static io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage.SAVING;
import static io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage.VOXELIZING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MineSplatUiFlowTest {
    @Test
    void resolvesInitialPageFromWorkflowState() {
        assertEquals(MineSplatUiFlow.Page.SOURCE,
                MineSplatUiFlow.initialPage(snapshot(GenerationState.IDLE, false)));
        assertEquals(MineSplatUiFlow.Page.SOURCE,
                MineSplatUiFlow.initialPage(snapshot(GenerationState.CANCELLED, false)));
        assertEquals(PROGRESS,
                MineSplatUiFlow.initialPage(snapshot(GenerationState.FAILED, false)));
        assertEquals(PROGRESS,
                MineSplatUiFlow.initialPage(snapshot(GenerationState.IDLE, true)));
        assertEquals(MineSplatUiFlow.Page.RESULT,
                MineSplatUiFlow.initialPage(snapshot(GenerationState.SUCCEEDED, false)));

        for (GenerationState state : GenerationState.values()) {
            if (state.active()) {
                assertEquals(PROGRESS, MineSplatUiFlow.initialPage(snapshot(state, false)),
                        () -> "Unexpected page for " + state);
            }
        }
    }

    @Test
    void groupsInternalStatesIntoFiveUserFacingStages() {
        assertEquals(PREPARING,
                MineSplatUiFlow.progressStage(GenerationState.UPLOADING));
        assertEquals(GENERATING,
                MineSplatUiFlow.progressStage(GenerationState.GENERATION_QUEUED));
        assertEquals(GENERATING,
                MineSplatUiFlow.progressStage(GenerationState.GENERATION_RUNNING));
        assertEquals(VOXELIZING,
                MineSplatUiFlow.progressStage(GenerationState.VOXELIZATION_QUEUED));
        assertEquals(VOXELIZING,
                MineSplatUiFlow.progressStage(GenerationState.VOXELIZATION_RUNNING));
        assertEquals(CONVERTING,
                MineSplatUiFlow.progressStage(GenerationState.DOWNLOADING));
        assertEquals(CONVERTING,
                MineSplatUiFlow.progressStage(GenerationState.CONVERTING));
        assertEquals(SAVING,
                MineSplatUiFlow.progressStage(GenerationState.SAVING));
        assertEquals(SAVING,
                MineSplatUiFlow.progressStage(GenerationState.PLACING));
        assertEquals(SAVING,
                MineSplatUiFlow.progressStage(GenerationState.SUCCEEDED));
        assertNull(MineSplatUiFlow.progressStage(GenerationState.IDLE));
        assertNull(MineSplatUiFlow.progressStage(GenerationState.FAILED));
        assertNull(MineSplatUiFlow.progressStage(GenerationState.CANCELLED));
    }

    private static GenerationSnapshot snapshot(GenerationState state, boolean pollingPaused) {
        return new GenerationSnapshot(
                state,
                "status",
                state == GenerationState.FAILED ? "failure" : null,
                null,
                null,
                false,
                pollingPaused,
                64,
                0,
                0,
                0,
                0,
                Map.of(),
                null,
                OutputMode.LITEMATICA,
                -1.0,
                -1.0);
    }
}
