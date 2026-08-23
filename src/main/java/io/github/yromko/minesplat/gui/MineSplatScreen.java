package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.client.MineSplatDraft;
import io.github.yromko.minesplat.cnb.CnbBlueprintStore;
import io.github.yromko.minesplat.cnb.CnbPlacementController;
import io.github.yromko.minesplat.config.GenerationSourceMode;
import io.github.yromko.minesplat.config.InferenceMode;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.gui.MineSplatUiFlow.Page;
import io.github.yromko.minesplat.gui.MineSplatUiFlow.ProgressStage;
import io.github.yromko.minesplat.inference.InferenceTarget;
import io.github.yromko.minesplat.inference.LocalModelManager;
import io.github.yromko.minesplat.inference.LocalModelSnapshot;
import io.github.yromko.minesplat.inference.LocalModelState;
import io.github.yromko.minesplat.inference.LocalRuntimeManager;
import io.github.yromko.minesplat.inference.LocalRuntimeSnapshot;
import io.github.yromko.minesplat.inference.LocalRuntimeState;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.palette.CustomPalette;
import io.github.yromko.minesplat.palette.CustomPaletteStore;
import io.github.yromko.minesplat.util.FileNames;
import io.github.yromko.minesplat.util.ImageFiles;
import io.github.yromko.minesplat.util.PreviewImages;
import io.github.yromko.minesplat.workflow.GenerationRequest;
import io.github.yromko.minesplat.workflow.GenerationSource;
import io.github.yromko.minesplat.workflow.GenerationSnapshot;
import io.github.yromko.minesplat.workflow.GenerationState;
import io.github.yromko.minesplat.workflow.MineSplatController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MineSplatScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 420;
    private static final int HEADER_Y = 27;
    private static final int WIZARD_Y = 53;
    private static final int CONTENT_TOP = 76;
    private static final int GAP = 6;
    private static final int BLOCK_LABEL_GAP = 14;
    private static final int BLOCK_ROW_GAP = 38;
    private static final Identifier SOURCE_PREVIEW_TEXTURE =
            Identifier.of("minesplat", "source_preview");

    private final Screen parent;
    private final MineSplatConfig config;
    private final BlockPalette palette;
    private final CustomPaletteStore customPalettes;
    private final MineSplatController controller;
    private final MineSplatDraft draft;
    private final CnbPlacementController cnbPlacement;
    private final CnbBlueprintStore cnbBlueprints;
    private final LocalModelManager localModels;
    private final LocalRuntimeManager localRuntime;

    private volatile GenerationSnapshot snapshot;
    private volatile LocalModelSnapshot coreModelSnapshot;
    private volatile LocalModelSnapshot textModelSnapshot;
    private volatile LocalRuntimeSnapshot runtimeSnapshot;
    private AutoCloseable generationSubscription;
    private AutoCloseable coreSubscription;
    private AutoCloseable textSubscription;
    private AutoCloseable runtimeSubscription;

    private Page page;
    private ProgressStage lastProgressStage;
    private GenerationState renderedState;
    private boolean renderedPollingPaused;
    private boolean advancedSource;
    private boolean detailsExpanded;
    private int panelWidth;
    private int left;

    private ButtonWidget libraryButton;
    private ButtonWidget sourceContinueButton;
    private ButtonWidget generateButton;
    private ButtonWidget cancelButton;
    private ButtonWidget retryConnectionButton;
    private ButtonWidget placeButton;
    private EditBoxWidget prompt;
    private TextFieldWidget seed;
    private TextFieldWidget schematicName;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;

    private Path previewPath;
    private Identifier previewIdentifier;
    private NativeImageBackedTexture previewTexture;
    private int previewImageWidth;
    private int previewImageHeight;
    private String localMessage;
    private boolean localMessageError;

    public MineSplatScreen(
            Screen parent,
            MineSplatConfig config,
            BlockPalette palette,
            CustomPaletteStore customPalettes,
            MineSplatController controller,
            MineSplatDraft draft,
            CnbPlacementController cnbPlacement,
            CnbBlueprintStore cnbBlueprints,
            LocalModelManager localModels,
            LocalRuntimeManager localRuntime
    ) {
        super(Text.translatable("minesplat.title"));
        this.parent = parent;
        this.config = config;
        this.palette = palette;
        this.customPalettes = customPalettes;
        this.controller = controller;
        this.draft = draft;
        this.cnbPlacement = cnbPlacement;
        this.cnbBlueprints = cnbBlueprints;
        this.localModels = localModels;
        this.localRuntime = localRuntime;
        this.snapshot = controller.snapshot();
        this.coreModelSnapshot = localModels.snapshot();
        this.textModelSnapshot = localModels.textSnapshot();
        this.runtimeSnapshot = localRuntime.snapshot();
        this.lastProgressStage = MineSplatUiFlow.progressStage(snapshot.state());
    }

    @Override
    protected void init() {
        closeSubscriptions();
        generationSubscription = controller.listen(value -> {
            snapshot = value;
            ProgressStage stage = MineSplatUiFlow.progressStage(value.state());
            if (stage != null) {
                lastProgressStage = stage;
            }
        });
        coreSubscription = localModels.listen(value -> coreModelSnapshot = value);
        textSubscription = localModels.listenText(value -> textModelSnapshot = value);
        runtimeSubscription = localRuntime.listen(value -> runtimeSnapshot = value);
        if (page == null) {
            page = MineSplatUiFlow.initialPage(snapshot);
        }
        buildWidgets();
    }

    private void buildWidgets() {
        clearChildren();
        clearWidgetReferences();
        panelWidth = Math.min(MAX_PANEL_WIDTH, width - 32);
        left = (width - panelWidth) / 2;
        buildHeader();
        switch (page) {
            case SOURCE -> buildSourcePage();
            case BLOCKS -> buildBlocksPage();
            case PROGRESS -> buildProgressPage();
            case RESULT -> buildResultPage();
        }
        renderedState = snapshot.state();
        renderedPollingPaused = snapshot.pollingPaused();
        updateControls();
    }

    private void buildHeader() {
        int actionWidth = Math.min(96, (panelWidth - GAP) / 2);
        int rowWidth = actionWidth * 2 + GAP;
        int rowLeft = left + (panelWidth - rowWidth) / 2;
        addDrawableChild(MineSplatButton.secondary(
                rowLeft, HEADER_Y, actionWidth, 18,
                Text.translatable("minesplat.settings"), ignored -> openSettings()));
        libraryButton = addDrawableChild(MineSplatButton.secondary(
                rowLeft + actionWidth + GAP,
                HEADER_Y, actionWidth, 18,
                Text.translatable("minesplat.saved"), ignored -> openLibrary()));
    }

    private void buildSourcePage() {
        int sourceWidth = Math.min(116, (panelWidth - GAP) / 2);
        int sourceRowWidth = sourceWidth * 2 + GAP;
        int sourceLeft = left + (panelWidth - sourceRowWidth) / 2;
        addDrawableChild(MineSplatButton.choice(
                sourceLeft,
                CONTENT_TOP,
                sourceWidth,
                20,
                Text.translatable("minesplat.source.image"),
                draft.sourceMode() == GenerationSourceMode.IMAGE,
                ignored -> selectSourceMode(GenerationSourceMode.IMAGE)));
        addDrawableChild(MineSplatButton.choice(
                sourceLeft + sourceWidth + GAP,
                CONTENT_TOP,
                sourceWidth,
                20,
                Text.translatable("minesplat.source.prompt"),
                draft.sourceMode() == GenerationSourceMode.PROMPT,
                ignored -> selectSourceMode(GenerationSourceMode.PROMPT)));

        int advancedY = height - 54;
        int inputTop = CONTENT_TOP + 28;
        if (draft.sourceMode() == GenerationSourceMode.IMAGE) {
            int chooseY = advancedY - 24;
            int previewWidth = Math.min(280, panelWidth);
            previewLeft = left + (panelWidth - previewWidth) / 2;
            previewTop = inputTop;
            previewRight = previewLeft + previewWidth;
            previewBottom = Math.max(inputTop + 36, chooseY - 5);
            int chooseWidth = Math.min(240, panelWidth);
            ButtonWidget chooseImage = addDrawableChild(MineSplatButton.secondary(
                    left + (panelWidth - chooseWidth) / 2,
                    chooseY, chooseWidth, 20,
                    imageLabel(), ignored -> chooseImage()));
            chooseImage.setTooltip(Tooltip.of(
                    Text.translatable("minesplat.source.image.drop_hint")));
            ensurePreview();
        } else {
            int promptHeight = Math.max(40, advancedY - inputTop - GAP);
            prompt = EditBoxWidget.builder()
                    .x(left)
                    .y(inputTop)
                    .placeholder(Text.translatable("minesplat.prompt.placeholder"))
                    .build(
                            textRenderer,
                            panelWidth,
                            promptHeight,
                            Text.translatable("minesplat.prompt"));
            prompt.setMaxLength(8192);
            prompt.setText(draft.prompt());
            prompt.setChangeListener(draft::prompt);
            addDrawableChild(prompt);
        }

        int advancedButtonWidth = Math.min(164, panelWidth);
        int seedWidth = Math.min(150, panelWidth - advancedButtonWidth - GAP);
        int advancedRowWidth = advancedSource
                ? advancedButtonWidth + GAP + seedWidth : advancedButtonWidth;
        int advancedLeft = left + (panelWidth - advancedRowWidth) / 2;
        addDrawableChild(MineSplatButton.secondary(
                advancedLeft, advancedY, advancedButtonWidth, 20,
                Text.translatable(advancedSource
                        ? "minesplat.advanced.hide" : "minesplat.advanced.show"),
                ignored -> {
                    persistCurrentFields();
                    advancedSource = !advancedSource;
                    buildWidgets();
                }));
        if (advancedSource) {
            seed = new TextFieldWidget(
                    textRenderer,
                    advancedLeft + advancedButtonWidth + GAP,
                    advancedY,
                    seedWidth,
                    20,
                    Text.translatable("minesplat.seed"));
            seed.setMaxLength(19);
            seed.setText(Long.toString(config.seed()));
            seed.setTextPredicate(value -> value.matches("\\d{0,19}"));
            seed.setPlaceholder(Text.translatable("minesplat.seed"));
            addDrawableChild(seed);
        }

        int continueWidth = Math.min(190, panelWidth);
        sourceContinueButton = addDrawableChild(MineSplatButton.primary(
                left + (panelWidth - continueWidth) / 2,
                height - 28, continueWidth, 20,
                Text.translatable("minesplat.next.blocks"),
                ignored -> switchPage(Page.BLOCKS)));
    }

    private void buildBlocksPage() {
        int nameWidth = Math.min(320, panelWidth);
        schematicName = new TextFieldWidget(
                textRenderer,
                left + (panelWidth - nameWidth) / 2,
                CONTENT_TOP,
                nameWidth,
                20,
                Text.translatable("minesplat.name"));
        schematicName.setMaxLength(128);
        schematicName.setText(draft.schematicName());
        schematicName.setPlaceholder(Text.translatable("minesplat.name"));
        schematicName.setChangedListener(draft::schematicName);
        addDrawableChild(schematicName);

        VoxelPreset[] presets = VoxelPreset.values();
        int presetGap = 4;
        int presetWidth = Math.min(58,
                (panelWidth - presetGap * (presets.length - 1)) / presets.length);
        int presetRowWidth = presetWidth * presets.length
                + presetGap * (presets.length - 1);
        int presetLeft = left + (panelWidth - presetRowWidth) / 2;
        int presetsY = CONTENT_TOP + 40;
        for (int index = 0; index < presets.length; index++) {
            VoxelPreset value = presets[index];
            addDrawableChild(MineSplatButton.choice(
                    presetLeft + index * (presetWidth + presetGap),
                    presetsY,
                    presetWidth,
                    20,
                    Text.literal(Integer.toString(value.resolution())),
                    value == config.voxelPreset(),
                    ignored -> {
                        config.voxelPreset(value);
                        buildWidgets();
                    }));
        }

        int optionRowWidth = Math.min(360, panelWidth);
        int optionLeft = left + (panelWidth - optionRowWidth) / 2;
        int half = (optionRowWidth - GAP) / 2;
        int optionsY = presetsY + BLOCK_ROW_GAP;
        Text selectedPalette = selectedCustomPalette()
                .<Text>map(value -> Text.literal(value.name()))
                .orElseGet(() -> Text.translatable(
                        "minesplat.palette." + config.paletteProfile().id()));
        Text paletteLabel = Text.translatable("minesplat.palette")
                .append(": ")
                .append(selectedPalette);
        addDrawableChild(MineSplatButton.secondary(
                optionLeft, optionsY, half, 20, paletteLabel,
                ignored -> openPalettes()));
        addDrawableChild(MineSplatButton.secondary(
                optionLeft + half + GAP, optionsY, half, 20,
                Text.translatable("minesplat.blacklist.summary",
                        config.blacklistedBlocks().size()),
                ignored -> openBlacklist()));

        int outputY = optionsY + BLOCK_ROW_GAP;
        addDrawableChild(MineSplatButton.choice(
                optionLeft,
                outputY,
                half,
                20,
                Text.translatable("minesplat.output.litematica"),
                config.outputMode() == OutputMode.LITEMATICA,
                ignored -> selectOutput(OutputMode.LITEMATICA)));
        ButtonWidget cnbOutput = addDrawableChild(MineSplatButton.choice(
                optionLeft + half + GAP,
                outputY,
                half,
                20,
                Text.translatable("minesplat.output.chisels_and_bits"),
                config.outputMode() == OutputMode.CHISELS_AND_BITS,
                ignored -> selectOutput(OutputMode.CHISELS_AND_BITS)));
        if (!cnbPlacement.integration().available()) {
            cnbOutput.setTooltip(Tooltip.of(Text.literal(
                    cnbPlacement.integration().unavailableReason())));
        }

        int backWidth = Math.min(92, (panelWidth - GAP) / 2);
        int generateWidth = Math.min(190, panelWidth - backWidth - GAP);
        int footerRowWidth = backWidth + GAP + generateWidth;
        int footerLeft = left + (panelWidth - footerRowWidth) / 2;
        addDrawableChild(MineSplatButton.secondary(
                footerLeft, height - 28, backWidth, 20,
                Text.translatable("gui.back"), ignored -> switchPage(Page.SOURCE)));
        generateButton = addDrawableChild(MineSplatButton.primary(
                footerLeft + backWidth + GAP,
                height - 28, generateWidth, 20,
                Text.translatable(config.outputMode() == OutputMode.CHISELS_AND_BITS
                        ? "minesplat.generate_blueprint" : "minesplat.generate"),
                ignored -> startWorkflow()));
    }

    private void buildProgressPage() {
        int actionY = height - 28;
        if (snapshot.pollingPaused()) {
            int rowWidth = Math.min(306, panelWidth);
            int rowLeft = left + (panelWidth - rowWidth) / 2;
            int half = (rowWidth - GAP) / 2;
            retryConnectionButton = addDrawableChild(MineSplatButton.primary(
                    rowLeft, actionY, half, 20,
                    Text.translatable("minesplat.retry_connection"),
                    ignored -> controller.resumePolling()));
            addDrawableChild(MineSplatButton.secondary(
                    rowLeft + half + GAP, actionY, half, 20,
                    Text.translatable("minesplat.back.settings"),
                    ignored -> switchPage(Page.BLOCKS)));
        } else if (snapshot.state() == GenerationState.FAILED) {
            int rowWidth = Math.min(306, panelWidth);
            int rowLeft = left + (panelWidth - rowWidth) / 2;
            int half = (rowWidth - GAP) / 2;
            addDrawableChild(MineSplatButton.primary(
                    rowLeft, actionY, half, 20,
                    Text.translatable("minesplat.retry"), ignored -> startWorkflow()));
            addDrawableChild(MineSplatButton.secondary(
                    rowLeft + half + GAP, actionY, half, 20,
                    Text.translatable("minesplat.back.settings"),
                    ignored -> switchPage(Page.BLOCKS)));
        } else if (snapshot.state().active()) {
            int cancelWidth = Math.min(128, panelWidth);
            cancelButton = addDrawableChild(MineSplatButton.danger(
                    left + (panelWidth - cancelWidth) / 2,
                    actionY, cancelWidth, 20,
                    Text.translatable("minesplat.cancel"),
                    ignored -> controller.cancel()));
        } else {
            int backWidth = Math.min(144, panelWidth);
            addDrawableChild(MineSplatButton.secondary(
                    left + (panelWidth - backWidth) / 2,
                    actionY, backWidth, 20,
                    Text.translatable("minesplat.back.source"),
                    ignored -> switchPage(Page.SOURCE)));
        }

        int detailsWidth = Math.min(146, panelWidth);
        addDrawableChild(MineSplatButton.secondary(
                left + (panelWidth - detailsWidth) / 2,
                Math.min(height - 54, CONTENT_TOP + 94), detailsWidth, 20,
                Text.translatable(detailsExpanded
                        ? "minesplat.details.hide" : "minesplat.details.show"),
                ignored -> {
                    detailsExpanded = !detailsExpanded;
                    buildWidgets();
                }));
    }

    private void buildResultPage() {
        int rowWidth = Math.min(326, panelWidth);
        int rowLeft = left + (panelWidth - rowWidth) / 2;
        int half = (rowWidth - GAP) / 2;
        int firstRow = height - 54;
        addDrawableChild(MineSplatButton.secondary(
                rowLeft, firstRow, half, 20,
                Text.translatable("minesplat.result.change_blocks"),
                ignored -> switchPage(Page.BLOCKS)));
        if (snapshot.outputMode() == OutputMode.CHISELS_AND_BITS) {
            placeButton = addDrawableChild(MineSplatButton.primary(
                    rowLeft + half + GAP, firstRow, half, 20,
                    Text.translatable("minesplat.cnb.place"),
                    ignored -> placeBlueprint()));
        } else {
            addDrawableChild(MineSplatButton.secondary(
                    rowLeft + half + GAP, firstRow, half, 20,
                    Text.translatable("minesplat.saved"), ignored -> openLibrary()));
        }

        if (snapshot.outputMode() == OutputMode.CHISELS_AND_BITS) {
            addDrawableChild(MineSplatButton.secondary(
                    rowLeft, height - 28, half, 20,
                    Text.translatable("minesplat.saved"), ignored -> openLibrary()));
            addDrawableChild(MineSplatButton.primary(
                    rowLeft + half + GAP, height - 28, half, 20,
                    Text.translatable("minesplat.new_project"),
                    ignored -> newProject()));
        } else {
            int newProjectWidth = Math.min(156, panelWidth);
            addDrawableChild(MineSplatButton.primary(
                    left + (panelWidth - newProjectWidth) / 2,
                    height - 28, newProjectWidth, 20,
                    Text.translatable("minesplat.new_project"),
                    ignored -> newProject()));
        }
    }

    private void clearWidgetReferences() {
        libraryButton = null;
        sourceContinueButton = null;
        generateButton = null;
        cancelButton = null;
        retryConnectionButton = null;
        placeButton = null;
        prompt = null;
        seed = null;
        schematicName = null;
        previewLeft = 0;
        previewTop = 0;
        previewRight = 0;
        previewBottom = 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (page == Page.PROGRESS && snapshot.state() == GenerationState.SUCCEEDED) {
            switchPage(Page.RESULT);
            return;
        }
        if (page == Page.PROGRESS
                && (renderedState != snapshot.state()
                || renderedPollingPaused != snapshot.pollingPaused())) {
            buildWidgets();
            return;
        }
        updateControls();
    }

    private void updateControls() {
        if (libraryButton == null) {
            return;
        }
        libraryButton.active = !cnbPlacement.active();
        if (sourceContinueButton != null) {
            sourceContinueButton.active = validSource() && parsedSeed() != null;
        }
        if (generateButton != null) {
            generateButton.active = canStart();
        }
        if (cancelButton != null) {
            cancelButton.active = snapshot.canCancel();
        }
        if (retryConnectionButton != null) {
            retryConnectionButton.active = snapshot.pollingPaused();
        }
        if (placeButton != null) {
            placeButton.active = snapshot.outputFile() != null
                    && cnbPlacement.canPlaceNow()
                    && !cnbPlacement.active();
        }
    }

    private boolean canStart() {
        if (client == null || client.world == null || client.player == null) {
            return false;
        }
        if (!validSource() || parsedSeed() == null || snapshot.state().active()) {
            return false;
        }
        if (config.outputMode() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.integration().available()) {
            return false;
        }
        try {
            inferenceTarget();
            if (config.inferenceMode() != InferenceMode.LOCAL) {
                return true;
            }
            return localRuntime.supported()
                    && coreModelSnapshot.state() == LocalModelState.READY
                    && (draft.sourceMode() != GenerationSourceMode.PROMPT
                    || textModelSnapshot.state() == LocalModelState.READY);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean validSource() {
        if (draft.sourceMode() == GenerationSourceMode.IMAGE) {
            try {
                ImageFiles.validate(draft.image());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        try {
            io.github.yromko.minesplat.api.TripoSplatApiClient.validatePrompt(draft.prompt());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void selectSourceMode(GenerationSourceMode value) {
        persistCurrentFields();
        draft.sourceMode(value);
        localMessage = null;
        buildWidgets();
    }

    private void selectOutput(OutputMode value) {
        config.outputMode(value);
        localMessage = null;
        localMessageError = false;
        saveConfig();
        buildWidgets();
    }

    private void switchPage(Page next) {
        persistCurrentFields();
        page = next;
        localMessage = null;
        buildWidgets();
    }

    private void openSettings() {
        persistCurrentFields();
        client.setScreen(new MineSplatSettingsScreen(
                this, config, controller, localModels, localRuntime));
    }

    private void openBlacklist() {
        persistCurrentFields();
        client.setScreen(new BlacklistScreen(this, config, palette));
    }

    private void openPalettes() {
        persistCurrentFields();
        client.setScreen(new PaletteSelectionScreen(
                this, config, palette, customPalettes));
    }

    private void openLibrary() {
        persistCurrentFields();
        client.setScreen(new CnbBlueprintLibraryScreen(
                this, cnbBlueprints, cnbPlacement));
    }

    private void startWorkflow() {
        persistCurrentFields();
        Long parsedSeed = parsedSeed();
        if (parsedSeed == null) {
            showLocalError(Text.translatable("minesplat.invalid_seed").getString());
            return;
        }

        try {
            GenerationSource source = generationSource();
            InferenceTarget target = inferenceTarget();
            RuntimePalette runtimePalette = runtimePalette();
            String name = FileNames.sanitize(draft.schematicName());
            draft.schematicName(name);
            CompletableFuture<Path> flow;
            if (controller.canReuseGeneration(
                    target, source, parsedSeed, config.generationPreset())) {
                flow = controller.rebuildOrRevoxelize(
                        name,
                        config.voxelPreset(),
                        runtimePalette.profile(),
                        runtimePalette.blacklist(),
                        config.outputMode());
            } else {
                flow = controller.start(new GenerationRequest(
                        target,
                        source,
                        name,
                        parsedSeed,
                        config.generationPreset(),
                        config.voxelPreset(),
                        runtimePalette.profile(),
                        runtimePalette.blacklist(),
                        config.outputMode()));
            }
            page = Page.PROGRESS;
            localMessage = null;
            buildWidgets();
            flow.whenComplete((ignored, failure) -> {
                if (failure != null && client != null) {
                    client.execute(() -> {
                        localMessage = usefulMessage(failure);
                        localMessageError = true;
                    });
                }
            });
        } catch (Exception exception) {
            showLocalError(usefulMessage(exception));
        }
    }

    private GenerationSource generationSource() throws IOException {
        if (draft.sourceMode() == GenerationSourceMode.IMAGE) {
            ImageFiles.validate(draft.image());
            return new GenerationSource.Image(draft.image());
        }
        return new GenerationSource.Prompt(draft.prompt());
    }

    private InferenceTarget inferenceTarget() {
        if (config.inferenceMode() == InferenceMode.LOCAL) {
            return new InferenceTarget.Local(config.localDeviceIndex());
        }
        return new InferenceTarget.Remote(config.serverUrl());
    }

    private Optional<CustomPalette> selectedCustomPalette() {
        return customPalettes.find(config.customPaletteId());
    }

    private RuntimePalette runtimePalette() {
        Set<String> blacklist = new LinkedHashSet<>(config.blacklistedBlocks());
        Optional<CustomPalette> selected = selectedCustomPalette();
        if (selected.isEmpty()) {
            return new RuntimePalette(config.paletteProfile(), Set.copyOf(blacklist));
        }
        Set<String> allowed = selected.get().effectiveBlocks(palette);
        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                    Text.translatable("minesplat.palette_editor.empty").getString());
        }
        return new RuntimePalette(
                PaletteProfile.ALL,
                selected.get().combinedBlacklist(palette, blacklist));
    }

    private Long parsedSeed() {
        try {
            String value = seed == null ? Long.toString(config.seed()) : seed.getText();
            return value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record RuntimePalette(PaletteProfile profile, Set<String> blacklist) {
    }

    private void persistCurrentFields() {
        if (prompt != null) {
            draft.prompt(prompt.getText());
        }
        if (schematicName != null) {
            draft.schematicName(schematicName.getText());
        }
        Long value = parsedSeed();
        if (value != null) {
            config.seed(value);
        }
        saveConfig();
    }

    private void newProject() {
        if (controller.hasSession() && !snapshot.state().active()) {
            controller.finishSession();
        }
        draft.reset();
        advancedSource = false;
        detailsExpanded = false;
        localMessage = null;
        destroyPreview();
        page = Page.SOURCE;
        buildWidgets();
    }

    private void chooseImage() {
        Thread picker = new Thread(() -> {
            String selected;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(3);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.jpg"));
                filters.put(stack.UTF8("*.jpeg"));
                filters.flip();
                selected = TinyFileDialogs.tinyfd_openFileDialog(
                        Text.translatable("minesplat.choose_image").getString(),
                        null,
                        filters,
                        "PNG / JPEG",
                        false);
            }
            if (selected != null && client != null) {
                client.execute(() -> selectImage(Path.of(selected)));
            }
        }, "MineSplat file picker");
        picker.setDaemon(true);
        picker.start();
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        if (!paths.isEmpty()) {
            persistCurrentFields();
            draft.sourceMode(GenerationSourceMode.IMAGE);
            page = Page.SOURCE;
            selectImage(paths.get(0));
        }
    }

    private void selectImage(Path path) {
        try {
            ImageFiles.validate(path);
            Path selected = path.toAbsolutePath().normalize();
            draft.image(selected);
            if (draft.schematicName().isBlank()
                    || "minesplat".equals(draft.schematicName())) {
                String fileName = selected.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                draft.schematicName(FileNames.sanitize(
                        dot > 0 ? fileName.substring(0, dot) : fileName));
            }
            localMessage = null;
            localMessageError = false;
            destroyPreview();
            buildWidgets();
        } catch (Exception exception) {
            showLocalError(usefulMessage(exception));
        }
    }

    private void ensurePreview() {
        Path image = draft.image();
        if (image == null || image.equals(previewPath) || client == null) {
            return;
        }
        destroyPreview();
        try {
            NativeImage nativeImage = PreviewImages.load(image, 1024);
            NativeImageBackedTexture texture;
            try {
                texture = new NativeImageBackedTexture(
                        () -> "MineSplat source preview", nativeImage);
            } catch (RuntimeException | Error failure) {
                nativeImage.close();
                throw failure;
            }
            try {
                client.getTextureManager().registerTexture(
                        SOURCE_PREVIEW_TEXTURE, texture);
                previewImageWidth = nativeImage.getWidth();
                previewImageHeight = nativeImage.getHeight();
                previewTexture = texture;
                previewIdentifier = SOURCE_PREVIEW_TEXTURE;
                previewPath = image;
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        } catch (Exception exception) {
            showLocalError(usefulMessage(exception));
        }
    }

    private void destroyPreview() {
        if (previewIdentifier != null && client != null) {
            client.getTextureManager().destroyTexture(previewIdentifier);
        } else if (previewTexture != null) {
            previewTexture.close();
        }
        previewPath = null;
        previewIdentifier = null;
        previewTexture = null;
        previewImageWidth = 0;
        previewImageHeight = 0;
    }

    private Text imageLabel() {
        return draft.image() == null
                ? Text.translatable("minesplat.choose_image")
                : Text.literal(draft.image().getFileName().toString());
    }

    private StatusLine backendStatus() {
        if (config.inferenceMode() == InferenceMode.REMOTE) {
            try {
                new InferenceTarget.Remote(config.serverUrl());
                return new StatusLine(Text.translatable(
                        "minesplat.backend.remote.ready"), 0x55ff55);
            } catch (RuntimeException exception) {
                return new StatusLine(Text.translatable(
                        "minesplat.backend.remote.setup"), 0xffaa00);
            }
        }
        if (!localRuntime.supported()) {
            return new StatusLine(Text.translatable(
                    "minesplat.backend.local.unsupported"), 0xff5555);
        }
        if (coreModelSnapshot.state() != LocalModelState.READY) {
            return new StatusLine(Text.translatable(
                    "minesplat.backend.local.setup"), 0xffaa00);
        }
        if (runtimeSnapshot.state() == LocalRuntimeState.FAILED) {
            return new StatusLine(Text.translatable(
                    "minesplat.backend.local.failed"), 0xff5555);
        }
        if (runtimeSnapshot.state() == LocalRuntimeState.STARTING) {
            return new StatusLine(Text.translatable(
                    "minesplat.backend.local.starting"), 0xffff55);
        }
        if (draft.sourceMode() == GenerationSourceMode.PROMPT
                && textModelSnapshot.state() != LocalModelState.READY) {
            return new StatusLine(Text.translatable(
                    "minesplat.backend.local.prompt_setup"), 0xffaa00);
        }
        return new StatusLine(Text.translatable(
                "minesplat.backend.local.ready", config.localDeviceIndex()), 0x55ff55);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderPanel(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xffffff);
        renderWizard(context);
        switch (page) {
            case SOURCE -> renderSourcePage(context);
            case BLOCKS -> renderBlocksPage(context);
            case PROGRESS -> renderProgressPage(context);
            case RESULT -> renderResultPage(context);
        }
        if (localMessage != null && page != Page.PROGRESS) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal(trim(localMessage, 90)),
                    width / 2,
                    height - 68,
                    localMessageError ? 0xff5555 : 0x55ff55);
        }
    }

    private void renderPanel(DrawContext context) {
        MineSplatPanel.render(context, left, panelWidth, height);
    }

    private void renderWizard(DrawContext context) {
        Page[] pages = Page.values();
        int stepWidth = panelWidth / pages.length;
        int markerY = WIZARD_Y - 3;
        for (int index = 0; index < pages.length; index++) {
            Page value = pages[index];
            int centerX = left + stepWidth * index + stepWidth / 2;
            boolean complete = index < page.ordinal();
            boolean selected = value == page;
            int markerBackground = selected ? 0xff356b66
                    : complete ? 0xff244b46 : 0xff20262d;
            int markerBorder = selected ? 0xff73e0d0
                    : complete ? 0xff4fae9f : 0xff4a555f;
            int color = selected ? 0xffffff : complete ? 0x73e0d0 : 0x7c8791;
            if (index + 1 < pages.length) {
                int nextCenterX = left + stepWidth * (index + 1) + stepWidth / 2;
                context.drawHorizontalLine(
                        centerX + 7,
                        nextCenterX - 7,
                        markerY + 6,
                        index < page.ordinal() ? 0xff4fae9f : 0xff3a424a);
            }
            context.fill(
                    centerX - 6, markerY,
                    centerX + 6, markerY + 12,
                    markerBackground);
            context.drawStrokedRectangle(
                    centerX - 6, markerY, 12, 12, markerBorder);
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    complete ? Text.literal("✓") : Text.literal(Integer.toString(index + 1)),
                    centerX,
                    markerY + 2,
                    selected || complete ? 0xffffff : 0x8c969f);
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.wizard."
                            + value.name().toLowerCase(Locale.ROOT)),
                    centerX,
                    markerY + 15,
                    color);
        }
    }

    private void renderSourcePage(DrawContext context) {
        if (draft.sourceMode() == GenerationSourceMode.PROMPT) {
            return;
        }
        context.fill(previewLeft, previewTop, previewRight, previewBottom, 0x90000000);
        context.drawStrokedRectangle(
                previewLeft, previewTop,
                previewRight - previewLeft,
                previewBottom - previewTop,
                0xff808080);
        if (previewIdentifier == null || previewImageWidth <= 0 || previewImageHeight <= 0) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.source.image.drop_hint"),
                    width / 2,
                    previewTop + Math.max(8, (previewBottom - previewTop) / 2 - 4),
                    0xa0a0a0);
            return;
        }
        int availableWidth = previewRight - previewLeft - 8;
        int availableHeight = previewBottom - previewTop - 8;
        float scale = Math.min(
                (float) availableWidth / previewImageWidth,
                (float) availableHeight / previewImageHeight);
        int drawnWidth = Math.max(1, Math.round(previewImageWidth * scale));
        int drawnHeight = Math.max(1, Math.round(previewImageHeight * scale));
        int x = previewLeft + (previewRight - previewLeft - drawnWidth) / 2;
        int y = previewTop + (previewBottom - previewTop - drawnHeight) / 2;
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                previewIdentifier,
                x,
                y,
                0.0f,
                0.0f,
                drawnWidth,
                drawnHeight,
                previewImageWidth,
                previewImageHeight,
                previewImageWidth,
                previewImageHeight);
    }

    private void renderBlocksPage(DrawContext context) {
        int optionRowWidth = Math.min(360, panelWidth);
        int optionLeft = left + (panelWidth - optionRowWidth) / 2;
        int presetsY = CONTENT_TOP + 40;
        int optionsY = presetsY + BLOCK_ROW_GAP;
        int outputY = optionsY + BLOCK_ROW_GAP;
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.preset"),
                optionLeft,
                presetsY - BLOCK_LABEL_GAP,
                0xa0a0a0);
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.palette"),
                optionLeft,
                optionsY - BLOCK_LABEL_GAP,
                0xa0a0a0);
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.output"),
                optionLeft,
                outputY - BLOCK_LABEL_GAP,
                0xa0a0a0);
        String warning = startWarning();
        if (warning != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal(trim(warning, 90)),
                    width / 2,
                    outputY + 27,
                    0xffaa00);
        }
    }

    private void renderProgressPage(DrawContext context) {
        ProgressStage[] stages = ProgressStage.values();
        ProgressStage current = MineSplatUiFlow.progressStage(snapshot.state());
        if (current == null) {
            current = lastProgressStage;
        }
        int currentIndex = current == null ? -1 : current.ordinal();
        int stepWidth = panelWidth / stages.length;
        int y = CONTENT_TOP + 8;
        for (int index = 0; index < stages.length; index++) {
            int color = snapshot.state() == GenerationState.SUCCEEDED || index < currentIndex
                    ? 0x55ff55 : index == currentIndex ? 0xffff55 : 0x707070;
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.progress." + stages[index].id()),
                    left + stepWidth * index + stepWidth / 2,
                    y,
                    color);
        }

        int stateY = CONTENT_TOP + 34;
        context.drawCenteredTextWithShadow(
                textRenderer,
                stateText(snapshot),
                width / 2,
                stateY,
                snapshot.state() == GenerationState.FAILED ? 0xff5555 : 0xffffff);
        String status = localMessage != null ? localMessage
                : snapshot.error() != null ? snapshot.error() : snapshot.message();
        if (status != null && !status.isBlank()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal(trim(status, 90)),
                    width / 2,
                    stateY + 16,
                    snapshot.state() == GenerationState.FAILED || localMessageError
                            ? 0xff5555 : 0xa0a0a0);
        }
        if (detailsExpanded) {
            int detailsY = CONTENT_TOP + 122;
            if (snapshot.jobId() != null) {
                context.drawTextWithShadow(textRenderer,
                        Text.translatable("minesplat.details.job", snapshot.jobId()),
                        left, detailsY, 0x808080);
                detailsY += 12;
            }
            if (snapshot.device() != null) {
                context.drawTextWithShadow(textRenderer,
                        Text.translatable("minesplat.details.device", snapshot.device()),
                        left, detailsY, 0x808080);
                detailsY += 12;
            }
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("minesplat.details.resolution", snapshot.resolution()),
                    left, detailsY, 0x808080);
        }
    }

    private void renderResultPage(DrawContext context) {
        GenerationSnapshot value = snapshot;
        int y = CONTENT_TOP + 22;
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(value.outputMode() == OutputMode.CHISELS_AND_BITS
                        ? "minesplat.state.succeeded_cnb" : "minesplat.state.succeeded"),
                width / 2,
                y,
                0x55ff55);
        int nextY = y + 22;
        if (value.hasImageGenerationTime()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                            "minesplat.time.image",
                            formatSeconds(value.imageGenerationSeconds())),
                    width / 2,
                    nextY,
                    0xa0a0a0);
            nextY += 14;
        }
        if (value.hasModelGenerationTime()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                            "minesplat.time.model",
                            formatSeconds(value.modelGenerationSeconds())),
                    width / 2,
                    nextY,
                    0xa0a0a0);
            nextY += 14;
        }
        if (value.outputMode() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.canPlaceNow()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal(trim(cnbPlacement.placementUnavailableReason(), 90)),
                    width / 2,
                    Math.min(height - 68, nextY + 6),
                    0xffaa00);
        }
    }

    private String startWarning() {
        if (client == null || client.world == null) {
            return Text.translatable("minesplat.world_required").getString();
        }
        if (config.outputMode() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.integration().available()) {
            return cnbPlacement.integration().unavailableReason();
        }
        StatusLine backend = backendStatus();
        if (backend.color() != 0x55ff55) {
            return backend.text().getString();
        }
        return null;
    }

    private Text stateText(GenerationSnapshot value) {
        String key = "minesplat.state."
                + value.state().name().toLowerCase(Locale.ROOT);
        if (value.state() == GenerationState.SUCCEEDED
                && value.outputMode() == OutputMode.CHISELS_AND_BITS) {
            key = "minesplat.state.succeeded_cnb";
        }
        return Text.translatable(key);
    }

    private void placeBlueprint() {
        if (snapshot.outputFile() == null || !cnbPlacement.canPlaceNow()) {
            showLocalError(cnbPlacement.placementUnavailableReason());
            return;
        }
        cnbPlacement.start(snapshot.outputFile());
        client.setScreen(null);
    }

    private void saveConfig() {
        try {
            config.save();
        } catch (IOException exception) {
            showLocalError(usefulMessage(exception));
        }
    }

    private void showLocalError(String value) {
        localMessage = value;
        localMessageError = true;
    }

    @Override
    public void removed() {
        persistCurrentFields();
        closeSubscriptions();
        destroyPreview();
    }

    @Override
    public void close() {
        persistCurrentFields();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void closeSubscriptions() {
        generationSubscription = close(generationSubscription);
        coreSubscription = close(coreSubscription);
        textSubscription = close(textSubscription);
        runtimeSubscription = close(runtimeSubscription);
    }

    private static AutoCloseable close(AutoCloseable value) {
        if (value != null) {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 1) + "…";
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private static String usefulMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record StatusLine(Text text, int color) {
    }
}
