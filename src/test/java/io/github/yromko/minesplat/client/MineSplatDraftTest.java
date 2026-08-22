package io.github.yromko.minesplat.client;

import io.github.yromko.minesplat.config.GenerationSourceMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MineSplatDraftTest {
    @Test
    void resetClearsProjectFieldsAndRestoresImageMode() {
        MineSplatDraft draft = new MineSplatDraft();
        draft.image(Path.of("input.png"));
        draft.prompt("a castle");
        draft.sourceMode(GenerationSourceMode.PROMPT);
        draft.schematicName("castle");

        draft.reset();

        assertNull(draft.image());
        assertEquals("", draft.prompt());
        assertEquals(GenerationSourceMode.IMAGE, draft.sourceMode());
        assertEquals("minesplat", draft.schematicName());
    }
}
