package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.api.ApiModels.Device;
import io.github.yromko.minesplat.client.MineSplatDraft;
import io.github.yromko.minesplat.cnb.CnbBlueprintStore;
import io.github.yromko.minesplat.cnb.CnbPlacementController;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.config.GenerationSourceMode;
import io.github.yromko.minesplat.config.InferenceMode;
import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.inference.InferenceTarget;
import io.github.yromko.minesplat.inference.LocalModelManager;
import io.github.yromko.minesplat.inference.LocalModelSnapshot;
import io.github.yromko.minesplat.inference.LocalModelSet;
import io.github.yromko.minesplat.inference.LocalModelState;
import io.github.yromko.minesplat.inference.LocalRuntimeManager;
import io.github.yromko.minesplat.inference.LocalRuntimeSnapshot;
import io.github.yromko.minesplat.util.FileNames;
import io.github.yromko.minesplat.util.ImageFiles;
import io.github.yromko.minesplat.workflow.GenerationRequest;
import io.github.yromko.minesplat.workflow.GenerationSource;
import io.github.yromko.minesplat.workflow.GenerationSnapshot;
import io.github.yromko.minesplat.workflow.GenerationState;
import io.github.yromko.minesplat.workflow.MineSplatController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MineSplatScreen extends Screen {
    private static final List<GenerationState> STEPS = List.of(
            GenerationState.UPLOADING,
            GenerationState.GENERATION_RUNNING,
            GenerationState.VOXELIZATION_RUNNING,
            GenerationState.DOWNLOADING,
            GenerationState.CONVERTING,
            GenerationState.SAVING,
            GenerationState.PLACING);

    private final Screen parent;
    private final MineSplatConfig config;
    private final BlockPalette palette;
    private final MineSplatController controller;
    private final MineSplatDraft draft;
    private final CnbPlacementController cnbPlacement;
    private final CnbBlueprintStore cnbBlueprints;
    private final LocalModelManager localModels;
    private final LocalRuntimeManager localRuntime;
    private volatile GenerationSnapshot snapshot;
    private volatile LocalModelSnapshot modelSnapshot;
    private volatile LocalModelSnapshot textModelSnapshot;
    private volatile LocalRuntimeSnapshot runtimeSnapshot;
    private AutoCloseable subscription;
    private AutoCloseable modelSubscription;
    private AutoCloseable textModelSubscription;
    private AutoCloseable runtimeSubscription;

    private TextFieldWidget serverUrl;
    private CyclingButtonWidget<InferenceMode> inferenceMode;
    private ButtonWidget targetButton;
    private ButtonWidget modelFolderButton;
    private ButtonWidget localDeviceButton;
    private ButtonWidget coreModelsButton;
    private ButtonWidget textModelsButton;
    private CyclingButtonWidget<GenerationSourceMode> sourceMode;
    private TextFieldWidget prompt;
    private TextFieldWidget schematicName;
    private TextFieldWidget seed;
    private CyclingButtonWidget<VoxelPreset> preset;
    private CyclingButtonWidget<PaletteProfile> paletteProfile;
    private CyclingButtonWidget<OutputMode> outputMode;
    private ButtonWidget imageButton;
    private ButtonWidget createButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resumeButton;
    private ButtonWidget finishSessionButton;
    private ButtonWidget placeButton;
    private ButtonWidget libraryButton;
    private String localMessage;
    private boolean localMessageError;
    private boolean compactLayout;
    private int titleY;
    private int stepperY;
    private List<Device> availableDevices = List.of();

    public MineSplatScreen(
            Screen parent,
            MineSplatConfig config,
            BlockPalette palette,
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
        this.controller = controller;
        this.draft = draft;
        this.cnbPlacement = cnbPlacement;
        this.cnbBlueprints = cnbBlueprints;
        this.localModels = localModels;
        this.localRuntime = localRuntime;
        this.snapshot = controller.snapshot();
        this.modelSnapshot = localModels.snapshot();
        this.textModelSnapshot = localModels.textSnapshot();
        this.runtimeSnapshot = localRuntime.snapshot();
    }

    @Override
    protected void init() {
        closeSubscription();
        subscription = controller.listen(value -> snapshot = value);
        modelSubscription = localModels.listen(value -> modelSnapshot = value);
        textModelSubscription = localModels.listenText(value -> textModelSnapshot = value);
        runtimeSubscription = localRuntime.listen(value -> runtimeSnapshot = value);
        int panelWidth = Math.min(560, width - 20);
        int left = (width - panelWidth) / 2;
        int gap = 6;
        int testWidth = 116;
        compactLayout = height < 380;
        int widgetHeight = compactLayout ? 18 : 20;
        int serverY = compactLayout ? 18 : 35;
        int deviceY = compactLayout ? 38 : 61;
        int modelsY = compactLayout ? 58 : 87;
        int imageY = compactLayout ? 78 : 113;
        int identityY = compactLayout ? 98 : 139;
        int optionsY = compactLayout ? 118 : 165;
        int outputY = compactLayout ? 138 : 191;
        int blacklistY = compactLayout ? 138 : 217;
        int actionsY = compactLayout ? 158 : 243;
        int placementY = compactLayout ? 178 : 269;
        titleY = compactLayout ? 4 : 15;
        stepperY = compactLayout
                ? Math.max(placementY + widgetHeight + 5, height - 44)
                : 275;

        int modeWidth = 90;
        int folderWidth = 26;
        inferenceMode = addDrawableChild(CyclingButtonWidget.<InferenceMode>builder(
                        value -> Text.translatable("minesplat.inference." + value.id()))
                .values(InferenceMode.values())
                .initially(config.inferenceMode())
                .build(left, serverY, modeWidth, widgetHeight,
                        Text.translatable("minesplat.inference"),
                        (button, value) -> switchInferenceMode(value)));
        serverUrl = new TextFieldWidget(
                textRenderer,
                left + modeWidth + gap,
                serverY,
                panelWidth - modeWidth - testWidth - folderWidth - gap * 3,
                widgetHeight,
                Text.translatable("minesplat.server_url"));
        serverUrl.setMaxLength(2048);
        addDrawableChild(serverUrl);
        modelFolderButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("…"), ignored -> chooseModelDirectory())
                .dimensions(
                        left + panelWidth - testWidth - folderWidth - gap,
                        serverY,
                        folderWidth,
                        widgetHeight)
                .build());
        targetButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.test_api"), ignored -> targetAction())
                .dimensions(
                        left + panelWidth - testWidth,
                        serverY,
                        testWidth,
                        widgetHeight)
                .build());

        localDeviceButton = addDrawableChild(ButtonWidget.builder(
                        localDeviceLabel(), ignored -> cycleLocalDevice())
                .dimensions(left, deviceY, panelWidth, widgetHeight).build());
        applyTargetMode();

        int half = (panelWidth - gap) / 2;
        coreModelsButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.models.core.install"),
                        ignored -> modelAction(LocalModelSet.CORE))
                .dimensions(left, modelsY, half, widgetHeight).build());
        textModelsButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.models.text.install"),
                        ignored -> modelAction(LocalModelSet.TEXT))
                .dimensions(left + half + gap, modelsY, half, widgetHeight).build());

        int sourceWidth = 104;
        sourceMode = addDrawableChild(CyclingButtonWidget.<GenerationSourceMode>builder(
                        value -> Text.translatable("minesplat.source." + value.id()))
                .values(GenerationSourceMode.values())
                .initially(draft.sourceMode())
                .build(left, imageY, sourceWidth, widgetHeight,
                        Text.translatable("minesplat.source"),
                        (button, value) -> {
                            draft.sourceMode(value);
                            updateSourceControls();
                        }));
        imageButton = addDrawableChild(ButtonWidget.builder(
                        imageLabel(), ignored -> chooseImage())
                .dimensions(left + sourceWidth + gap, imageY,
                        panelWidth - sourceWidth - gap, widgetHeight).build());
        prompt = new TextFieldWidget(
                textRenderer,
                left + sourceWidth + gap,
                imageY,
                panelWidth - sourceWidth - gap,
                widgetHeight,
                Text.translatable("minesplat.prompt"));
        prompt.setMaxLength(8192);
        prompt.setText(draft.prompt());
        prompt.setPlaceholder(Text.translatable("minesplat.prompt.placeholder"));
        addDrawableChild(prompt);
        updateSourceControls();

        schematicName = new TextFieldWidget(
                textRenderer, left, identityY, half, widgetHeight,
                Text.translatable("minesplat.name"));
        schematicName.setMaxLength(128);
        schematicName.setText(draft.schematicName());
        schematicName.setPlaceholder(Text.translatable("minesplat.name"));
        addDrawableChild(schematicName);

        seed = new TextFieldWidget(
                textRenderer, left + half + gap, identityY, half, widgetHeight,
                Text.translatable("minesplat.seed"));
        seed.setMaxLength(19);
        seed.setText(Long.toString(config.seed()));
        seed.setTextPredicate(value -> value.matches("\\d{0,19}"));
        seed.setPlaceholder(Text.translatable("minesplat.seed"));
        addDrawableChild(seed);

        preset = addDrawableChild(CyclingButtonWidget.<VoxelPreset>builder(
                        value -> Text.literal(value.id() + " · " + value.resolution() + "³"))
                .values(VoxelPreset.values())
                .initially(config.voxelPreset())
                .build(left, optionsY, half, widgetHeight,
                        Text.translatable("minesplat.preset"),
                        (button, value) -> config.voxelPreset(value)));
        paletteProfile = addDrawableChild(CyclingButtonWidget.<PaletteProfile>builder(
                        value -> Text.literal(value.id()))
                .values(PaletteProfile.values())
                .initially(config.paletteProfile())
                .build(left + half + gap, optionsY, half, widgetHeight,
                        Text.translatable("minesplat.palette"),
                        (button, value) -> config.paletteProfile(value)));

        int outputWidth = compactLayout ? half : panelWidth;
        outputMode = addDrawableChild(CyclingButtonWidget.<OutputMode>builder(
                        value -> Text.translatable(
                                "minesplat.output." + value.id()))
                .values(OutputMode.values())
                .initially(config.outputMode())
                .build(left, outputY, outputWidth, widgetHeight,
                        Text.translatable("minesplat.output"),
                        (button, value) -> {
                            config.outputMode(value);
                            localMessage = value == OutputMode.CHISELS_AND_BITS
                                    && !cnbPlacement.integration().available()
                                    ? cnbPlacement.integration().unavailableReason()
                                    : null;
                            localMessageError = localMessage != null;
                        }));

        int blacklistX = compactLayout ? left + half + gap : left;
        int blacklistWidth = compactLayout ? half : panelWidth;
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable(
                                "minesplat.blacklist.count",
                                config.blacklistedBlocks().size()),
                        ignored -> {
                            persistFields();
                            client.setScreen(new BlacklistScreen(this, config, palette));
                        })
                .dimensions(
                        blacklistX,
                        blacklistY,
                        blacklistWidth,
                        widgetHeight)
                .build());

        int actionWidth = (panelWidth - gap * 3) / 4;
        createButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.generate"), ignored -> generate())
                .dimensions(left, actionsY, actionWidth, widgetHeight).build());
        cancelButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cancel"), ignored -> controller.cancel())
                .dimensions(
                        left + (actionWidth + gap),
                        actionsY,
                        actionWidth,
                        widgetHeight)
                .build());
        resumeButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.resume_polling"),
                        ignored -> controller.resumePolling())
                .dimensions(
                        left + (actionWidth + gap) * 2,
                        actionsY,
                        actionWidth,
                        widgetHeight)
                .build());
        finishSessionButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.close_session"),
                        ignored -> controller.finishSession())
                .dimensions(
                        left + (actionWidth + gap) * 3,
                        actionsY,
                        actionWidth,
                        widgetHeight)
                .build());
        placeButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cnb.place"),
                        ignored -> placeBlueprint())
                .dimensions(left, placementY, half, widgetHeight).build());
        libraryButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cnb.library"),
                        ignored -> {
                            persistFields();
                            client.setScreen(new CnbBlueprintLibraryScreen(
                                    this, cnbBlueprints, cnbPlacement));
                        })
                .dimensions(
                        left + half + gap,
                        placementY,
                        half,
                        widgetHeight)
                .build());
        updateControls();
    }

    @Override
    public void tick() {
        super.tick();
        updateControls();
    }

    private void updateControls() {
        if (createButton == null) {
            return;
        }
        GenerationSnapshot value = snapshot;
        boolean hasWorld = client != null && client.world != null && client.player != null;
        boolean validSeed = parseSeed() != null;
        boolean validTarget;
        try {
            inferenceTarget();
            validTarget = inferenceMode.getValue() == InferenceMode.REMOTE
                    || (localRuntime.supported()
                    && modelSnapshot.state() == LocalModelState.READY);
        } catch (RuntimeException exception) {
            validTarget = false;
        }
        boolean outputAvailable = outputMode.getValue() != OutputMode.CHISELS_AND_BITS
                || cnbPlacement.integration().available();
        boolean validSource = validSource();
        boolean localPromptReady = inferenceMode.getValue() != InferenceMode.LOCAL
                || sourceMode.getValue() != GenerationSourceMode.PROMPT
                || textModelSnapshot.state() == LocalModelState.READY;
        createButton.active = hasWorld && validSource && validSeed
                && validTarget && outputAvailable && !value.state().active();
        createButton.active &= localPromptReady;
        cancelButton.active = value.canCancel();
        resumeButton.active = value.pollingPaused();
        finishSessionButton.active = controller.hasSession() && !value.state().active();
        placeButton.active = value.state() == GenerationState.SUCCEEDED
                && value.outputMode() == OutputMode.CHISELS_AND_BITS
                && value.outputFile() != null
                && cnbPlacement.canPlaceNow()
                && !cnbPlacement.active();
        libraryButton.active = !cnbPlacement.active();
        createButton.setMessage(Text.translatable(
                outputMode.getValue() == OutputMode.CHISELS_AND_BITS
                        ? "minesplat.generate_blueprint"
                        : "minesplat.generate"));
        imageButton.setMessage(imageLabel());
        inferenceMode.active = !value.state().active();
        sourceMode.active = !value.state().active();
        prompt.active = !value.state().active();
        updateSourceControls();
        boolean local = inferenceMode.getValue() == InferenceMode.LOCAL;
        modelFolderButton.visible = local;
        modelFolderButton.active = local
                && !localModels.installing()
                && !value.state().active();
        localDeviceButton.visible = local;
        localDeviceButton.active = local
                && modelSnapshot.state() == LocalModelState.READY
                && !value.state().active();
        localDeviceButton.setMessage(localDeviceLabel());
        coreModelsButton.visible = local;
        textModelsButton.visible = local;
        updateModelButton(coreModelsButton, LocalModelSet.CORE, modelSnapshot, value);
        updateModelButton(textModelsButton, LocalModelSet.TEXT, textModelSnapshot, value);
        serverUrl.active = !value.state().active()
                && (!local || !localModels.installing());
        if (!local) {
            targetButton.setMessage(Text.translatable("minesplat.test_api"));
            targetButton.active = !value.state().active();
        } else {
            targetButton.setMessage(Text.translatable("minesplat.local.test"));
            targetButton.active = modelSnapshot.state() == LocalModelState.READY
                    && !value.state().active() && !localModels.installing();
        }
    }

    private boolean validSource() {
        if (sourceMode.getValue() == GenerationSourceMode.IMAGE) {
            return draft.image() != null;
        }
        try {
            io.github.yromko.minesplat.api.TripoSplatApiClient.validatePrompt(
                    prompt.getText());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void updateSourceControls() {
        if (sourceMode == null || imageButton == null || prompt == null) {
            return;
        }
        boolean image = sourceMode.getValue() == GenerationSourceMode.IMAGE;
        imageButton.visible = image;
        prompt.visible = !image;
    }

    private void updateModelButton(
            ButtonWidget button,
            LocalModelSet set,
            LocalModelSnapshot models,
            GenerationSnapshot generation
    ) {
        boolean activeInstall = localModels.installing()
                && localModels.activeSet() == set;
        if (activeInstall) {
            button.setMessage(Text.translatable("minesplat.models.cancel"));
            button.active = true;
            return;
        }
        String key = set == LocalModelSet.CORE ? "core" : "text";
        if (models.state() == LocalModelState.READY) {
            button.setMessage(Text.translatable("minesplat.models." + key + ".ready"));
        } else {
            button.setMessage(Text.translatable("minesplat.models." + key + ".install"));
        }
        button.active = localRuntime.supported()
                && models.state() != LocalModelState.CHECKING
                && models.state() != LocalModelState.READY
                && !localModels.installing()
                && !generation.state().active();
    }

    private void targetAction() {
        if (inferenceMode.getValue() != InferenceMode.LOCAL) {
            testApi();
            return;
        }
        syncModelDirectory().whenComplete((ready, failure) -> {
            if (failure != null) {
                client.execute(() -> {
                    localMessage = usefulMessage(failure);
                    localMessageError = true;
                });
            } else if (ready) {
                client.execute(this::testApi);
            } else {
                client.execute(() -> {
                    localMessage = Text.translatable(
                            "minesplat.local.status.models_missing").getString();
                    localMessageError = true;
                });
            }
        });
    }

    private void modelAction(LocalModelSet set) {
        if (localModels.installing() && localModels.activeSet() == set) {
            localModels.cancelInstall();
            return;
        }
        syncModelDirectory().whenComplete((ignored, failure) -> {
            if (failure != null) {
                client.execute(() -> {
                    localMessage = usefulMessage(failure);
                    localMessageError = true;
                });
                return;
            }
            client.execute(() -> {
                localMessage = null;
                localMessageError = false;
                localRuntime.stop();
                localModels.install(set);
            });
        });
    }

    private void testApi() {
        try {
            InferenceTarget target = inferenceTarget();
            if (target instanceof InferenceTarget.Remote remote) {
                serverUrl.setText(remote.baseUrl());
            }
            persistFields();
            localMessage = Text.translatable("minesplat.api_testing").getString();
            localMessageError = false;
            controller.testConnection(target).whenComplete((info, failure) ->
                    client.execute(() -> {
                        if (failure != null) {
                            localMessage = usefulMessage(failure);
                            localMessageError = true;
                        } else {
                            availableDevices = info.devices();
                            String device = info.selectedDevice() == null
                                    ? Text.translatable("minesplat.device_none").getString()
                                    : info.selectedDevice().name();
                            localMessage = Text.translatable("minesplat.api_ok", device).getString();
                            localMessageError = false;
                        }
                    }));
        } catch (RuntimeException exception) {
            localMessage = usefulMessage(exception);
            localMessageError = true;
        }
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
            if (selected != null) {
                client.execute(() -> selectImage(Path.of(selected)));
            }
        }, "MineSplat file picker");
        picker.setDaemon(true);
        picker.start();
    }

    @Override
    public void filesDragged(List<Path> paths) {
        if (!paths.isEmpty()) {
            sourceMode.setValue(GenerationSourceMode.IMAGE);
            draft.sourceMode(GenerationSourceMode.IMAGE);
            updateSourceControls();
            selectImage(paths.getFirst());
        }
    }

    private void selectImage(Path path) {
        try {
            ImageFiles.validate(path);
            draft.image(path.toAbsolutePath().normalize());
            if (schematicName.getText().isBlank()
                    || "minesplat".equals(schematicName.getText())) {
                String fileName = path.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                schematicName.setText(FileNames.sanitize(
                        dot > 0 ? fileName.substring(0, dot) : fileName));
            }
            localMessage = path.getFileName().toString();
            localMessageError = false;
        } catch (Exception exception) {
            localMessage = exception.getMessage();
            localMessageError = true;
        }
    }

    private void generate() {
        Long parsedSeed = parseSeed();
        if (parsedSeed == null) {
            return;
        }
        GenerationSource source;
        try {
            source = generationSource();
            inferenceTarget();
            persistFields();
        } catch (Exception exception) {
            localMessage = exception.getMessage();
            localMessageError = true;
            return;
        }

        String name = FileNames.sanitize(schematicName.getText());
        draft.schematicName(name);
        InferenceTarget target = inferenceTarget();
        if (controller.canReuseGeneration(target, source, parsedSeed)) {
            controller.rebuildOrRevoxelize(
                    name,
                    preset.getValue(),
                    paletteProfile.getValue(),
                    config.blacklistedBlocks(),
                    outputMode.getValue());
        } else {
            controller.start(new GenerationRequest(
                    target,
                    source,
                    name,
                    parsedSeed,
                    preset.getValue(),
                    paletteProfile.getValue(),
                    config.blacklistedBlocks(),
                    outputMode.getValue()));
        }
        localMessage = null;
    }

    private GenerationSource generationSource() throws IOException {
        if (sourceMode.getValue() == GenerationSourceMode.IMAGE) {
            ImageFiles.validate(draft.image());
            return new GenerationSource.Image(draft.image());
        }
        return new GenerationSource.Prompt(prompt.getText());
    }

    private Long parseSeed() {
        try {
            String value = seed == null ? "" : seed.getText();
            return value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void persistFields() {
        if (inferenceMode != null && inferenceMode.getValue() == InferenceMode.LOCAL) {
            config.localModelDirectory(serverUrl.getText());
        } else if (serverUrl != null) {
            config.serverUrl(serverUrl.getText());
        }
        Long value = parseSeed();
        if (value != null) {
            config.seed(value);
        }
        config.voxelPreset(preset.getValue());
        config.paletteProfile(paletteProfile.getValue());
        config.outputMode(outputMode.getValue());
        draft.schematicName(schematicName.getText());
        if (sourceMode != null) {
            draft.sourceMode(sourceMode.getValue());
        }
        if (prompt != null) {
            draft.prompt(prompt.getText());
        }
        try {
            config.save();
        } catch (IOException exception) {
            localMessage = exception.getMessage();
            localMessageError = true;
        }
    }

    private void switchInferenceMode(InferenceMode value) {
        InferenceMode previous = config.inferenceMode();
        if (previous == value) {
            return;
        }
        if (previous == InferenceMode.LOCAL) {
            config.localModelDirectory(serverUrl.getText());
        } else {
            config.serverUrl(serverUrl.getText());
        }
        if (controller.hasSession() && !snapshot.state().active()) {
            controller.finishSession();
        }
        localRuntime.stop();
        config.inferenceMode(value);
        applyTargetMode();
        persistFields();
    }

    private void applyTargetMode() {
        if (inferenceMode.getValue() == InferenceMode.LOCAL) {
            serverUrl.setText(localModels.directory().toString());
            serverUrl.setPlaceholder(Text.translatable("minesplat.models.directory"));
        } else {
            serverUrl.setText(config.serverUrl());
            serverUrl.setPlaceholder(Text.translatable("minesplat.server_url"));
        }
    }

    private InferenceTarget inferenceTarget() {
        if (inferenceMode.getValue() == InferenceMode.LOCAL) {
            Path entered = Path.of(serverUrl.getText()).toAbsolutePath().normalize();
            if (!entered.equals(localModels.directory())) {
                throw new IllegalStateException(
                        Text.translatable("minesplat.models.apply_directory").getString());
            }
            return new InferenceTarget.Local(config.localDeviceIndex());
        }
        return new InferenceTarget.Remote(serverUrl.getText());
    }

    private CompletableFuture<Boolean> syncModelDirectory() {
        try {
            Path entered = Path.of(serverUrl.getText()).toAbsolutePath().normalize();
            config.localModelDirectory(entered.toString());
            if (entered.equals(localModels.directory())) {
                return CompletableFuture.completedFuture(localModels.ready());
            }
            localRuntime.stop();
            return localModels.setDirectory(entered);
        } catch (RuntimeException exception) {
            localMessage = usefulMessage(exception);
            localMessageError = true;
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void chooseModelDirectory() {
        Thread picker = new Thread(() -> {
            String selected = TinyFileDialogs.tinyfd_selectFolderDialog(
                    Text.translatable("minesplat.models.choose_directory").getString(),
                    localModels.directory().toString());
            if (selected != null) {
                client.execute(() -> {
                    serverUrl.setText(Path.of(selected).toAbsolutePath().normalize().toString());
                    syncModelDirectory();
                });
            }
        }, "MineSplat model directory picker");
        picker.setDaemon(true);
        picker.start();
    }

    private void cycleLocalDevice() {
        if (availableDevices.isEmpty()) {
            testApi();
            return;
        }
        int current = config.localDeviceIndex();
        int position = 0;
        for (int index = 0; index < availableDevices.size(); index++) {
            if (availableDevices.get(index).index() == current) {
                position = index;
                break;
            }
        }
        Device next = availableDevices.get((position + 1) % availableDevices.size());
        if (controller.hasSession() && !snapshot.state().active()) {
            controller.finishSession();
        }
        localRuntime.stop();
        config.localDeviceIndex(next.index());
        persistFields();
    }

    private Text localDeviceLabel() {
        int index = config.localDeviceIndex();
        String name = availableDevices.stream()
                .filter(value -> value.index() == index)
                .map(Device::name)
                .findFirst()
                .orElse(runtimeSnapshot != null && runtimeSnapshot.device() != null
                        ? runtimeSnapshot.device() : "Vulkan device " + index);
        return Text.translatable("minesplat.local.device", index, name);
    }

    private Text imageLabel() {
        Path image = draft.image();
        return image == null
                ? Text.translatable("minesplat.choose_image")
                : Text.literal(image.getFileName().toString());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(
                textRenderer, title, width / 2, titleY, 0xffffff);

        GenerationSnapshot value = snapshot;
        int panelWidth = Math.min(560, width - 20);
        int left = (width - panelWidth) / 2;
        if (compactLayout) {
            context.fill(
                    left - 3,
                    stepperY - 4,
                    left + panelWidth + 3,
                    height - 3,
                    0xa0000000);
        }
        renderStepper(
                context, left, stepperY, panelWidth, value.state(), value.outputMode());

        int textY = stepperY + (compactLayout ? 13 : 20);
        String stateKey = "minesplat.state."
                + value.state().name().toLowerCase(Locale.ROOT);
        if (value.state() == GenerationState.SUCCEEDED
                && value.outputMode() == OutputMode.CHISELS_AND_BITS) {
            stateKey = "minesplat.state.succeeded_cnb";
        }
        Text stateText = Text.translatable(stateKey);
        context.drawCenteredTextWithShadow(
                textRenderer, stateText, width / 2, textY,
                value.state() == GenerationState.FAILED ? 0xff5555 : 0xffffff);
        textY += 13;

        if (compactLayout) {
            StatusLine status = compactStatus(value);
            if (status != null && textY <= height - 10) {
                context.drawCenteredTextWithShadow(
                        textRenderer,
                        status.text(),
                        width / 2,
                        textY,
                        status.color());
            }
            return;
        }

        if (client.world == null) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.world_required"),
                    width / 2, textY, 0xffaa00);
            textY += 13;
        }
        if (outputMode.getValue() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.integration().available()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    trim(cnbPlacement.integration().unavailableReason(), 90),
                    width / 2, textY, 0xff5555);
            textY += 13;
        } else if (outputMode.getValue() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.canPlaceNow()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    trim(cnbPlacement.placementUnavailableReason(), 90),
                    width / 2, textY, 0xffaa00);
            textY += 13;
        }
        StatusLine localStatus = localInferenceStatus();
        if (localStatus != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, localStatus.text(), width / 2, textY, localStatus.color());
            textY += 13;
        }
        if (localMessage != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, localMessage, width / 2, textY,
                    localMessageError ? 0xff5555 : 0x55ff55);
            textY += 13;
        } else if (value.error() != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, trim(value.error(), 90), width / 2, textY, 0xff5555);
            textY += 13;
        } else if (value.device() != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.device", value.device()),
                    width / 2, textY, 0xa0a0a0);
            textY += 13;
        }

        if (value.blockCount() > 0) {
            context.drawCenteredTextWithShadow(
                    textRenderer, resultText(value), width / 2, textY, 0x55ff55);
            textY += 13;
            String materials = value.materials().entrySet().stream()
                    .sorted(MapEntryComparator.INSTANCE)
                    .limit(4)
                    .map(entry -> entry.getKey().replace("minecraft:", "")
                            + " ×" + entry.getValue())
                    .reduce((leftValue, rightValue) -> leftValue + ", " + rightValue)
                    .orElse("");
            context.drawCenteredTextWithShadow(
                    textRenderer, trim(materials, 100), width / 2, textY, 0xa0a0a0);
            textY += 13;
        }
        if (value.outputFile() != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, trim(value.outputFile().toString(), 100),
                    width / 2, textY, 0xa0a0a0);
        }
    }

    private StatusLine compactStatus(GenerationSnapshot value) {
        if (localMessage != null) {
            return new StatusLine(
                    Text.literal(trim(localMessage, 90)),
                    localMessageError ? 0xff5555 : 0x55ff55);
        }
        StatusLine localStatus = localInferenceStatus();
        if (localStatus != null) {
            return localStatus;
        }
        if (value.error() != null) {
            return new StatusLine(
                    Text.literal(trim(value.error(), 90)), 0xff5555);
        }
        if (outputMode.getValue() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.integration().available()) {
            return new StatusLine(
                    Text.literal(trim(
                            cnbPlacement.integration().unavailableReason(), 90)),
                    0xff5555);
        }
        if ((value.state().active() || value.pollingPaused())
                && value.message() != null
                && !value.message().isBlank()) {
            return new StatusLine(
                    Text.literal(trim(value.message(), 90)), 0xffff55);
        }
        if (client.world == null) {
            return new StatusLine(
                    Text.translatable("minesplat.world_required"), 0xffaa00);
        }
        if (outputMode.getValue() == OutputMode.CHISELS_AND_BITS
                && !cnbPlacement.canPlaceNow()) {
            return new StatusLine(
                    Text.literal(trim(
                            cnbPlacement.placementUnavailableReason(), 90)),
                    0xffaa00);
        }
        if (value.blockCount() > 0) {
            return new StatusLine(resultText(value), 0x55ff55);
        }
        if (value.device() != null) {
            return new StatusLine(
                    Text.translatable("minesplat.device", value.device()),
                    0xa0a0a0);
        }
        if (value.outputFile() != null) {
            return new StatusLine(
                    Text.literal(trim(value.outputFile().toString(), 90)),
                    0xa0a0a0);
        }
        return null;
    }

    private StatusLine localInferenceStatus() {
        if (inferenceMode == null || inferenceMode.getValue() != InferenceMode.LOCAL) {
            return null;
        }
        if (!localRuntime.supported()) {
            return new StatusLine(
                    Text.translatable(
                            "minesplat.local.status.unsupported", localRuntime.platform()),
                    0xff5555);
        }
        StatusLine core = modelSetStatus(LocalModelSet.CORE, modelSnapshot, true);
        if (core != null) {
            return core;
        }
        boolean promptRequired = sourceMode != null
                && sourceMode.getValue() == GenerationSourceMode.PROMPT;
        StatusLine text = modelSetStatus(
                LocalModelSet.TEXT, textModelSnapshot, promptRequired);
        if (text != null) {
            return text;
        }
        return switch (runtimeSnapshot.state()) {
            case STARTING -> new StatusLine(
                    Text.translatable("minesplat.local.status.runtime_starting"), 0xffff55);
            case READY -> new StatusLine(Text.translatable(
                    "minesplat.local.status.runtime_ready", runtimeSnapshot.device()), 0x55ff55);
            case FAILED -> new StatusLine(Text.literal(trim(
                    runtimeSnapshot.error() == null
                            ? runtimeSnapshot.message() : runtimeSnapshot.error(), 90)), 0xff5555);
            default -> null;
        };
    }

    private StatusLine modelSetStatus(
            LocalModelSet set,
            LocalModelSnapshot models,
            boolean missingIsRelevant
    ) {
        String name = Text.translatable("minesplat.models." + set.id()).getString();
        if (models.state() == LocalModelState.CHECKING) {
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.models_checking_set", name), 0xffff55);
        }
        if (models.state() == LocalModelState.DOWNLOADING) {
            int percent = (int) Math.round(models.progress() * 100.0);
            String file = models.currentFile() == null ? "models" : models.currentFile();
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.models_downloading_set",
                    name, percent, file), 0xffff55);
        }
        if (models.state() == LocalModelState.CONVERTING) {
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.models_converting", name), 0xffff55);
        }
        if (models.state() == LocalModelState.MISSING && missingIsRelevant) {
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.models_missing_set", name), 0xffaa00);
        }
        if (models.state() == LocalModelState.FAILED) {
            return new StatusLine(
                    Text.literal(trim(models.error(), 90)), 0xff5555);
        }
        return null;
    }

    private Text resultText(GenerationSnapshot value) {
        if (value.outputMode() == OutputMode.CHISELS_AND_BITS
                && cnbPlacement.integration().available()) {
            int side = cnbPlacement.integration().bitsPerBlockSide();
            return Text.translatable(
                    "minesplat.result_bits",
                    value.width(), value.height(), value.depth(),
                    ceilDiv(value.width(), side),
                    ceilDiv(value.height(), side),
                    ceilDiv(value.depth(), side),
                    value.blockCount());
        }
        return Text.translatable(
                "minesplat.result",
                value.width(), value.height(), value.depth(), value.blockCount());
    }

    private void renderStepper(
            DrawContext context,
            int left,
            int y,
            int panelWidth,
            GenerationState current,
            OutputMode mode
    ) {
        List<GenerationState> steps = mode == OutputMode.CHISELS_AND_BITS
                ? STEPS.subList(0, STEPS.size() - 1)
                : STEPS;
        int currentIndex = stepIndex(current, mode);
        int stepWidth = panelWidth / steps.size();
        for (int index = 0; index < steps.size(); index++) {
            GenerationState step = steps.get(index);
            int color = index < currentIndex || current == GenerationState.SUCCEEDED
                    ? 0x55ff55
                    : index == currentIndex ? 0xffff55 : 0x707070;
            Text label = Text.translatable(
                    "minesplat.step." + step.name().toLowerCase(Locale.ROOT));
            context.drawCenteredTextWithShadow(
                    textRenderer, label,
                    left + stepWidth * index + stepWidth / 2,
                    y,
                    color);
        }
    }

    private static int stepIndex(GenerationState state, OutputMode mode) {
        int complete = mode == OutputMode.CHISELS_AND_BITS
                ? STEPS.size() - 1
                : STEPS.size();
        return switch (state) {
            case UPLOADING -> 0;
            case GENERATION_QUEUED, GENERATION_RUNNING -> 1;
            case VOXELIZATION_QUEUED, VOXELIZATION_RUNNING -> 2;
            case DOWNLOADING -> 3;
            case CONVERTING -> 4;
            case SAVING -> 5;
            case PLACING -> 6;
            case SUCCEEDED -> complete;
            default -> -1;
        };
    }

    private void placeBlueprint() {
        GenerationSnapshot value = snapshot;
        if (value.outputFile() == null || !cnbPlacement.canPlaceNow()) {
            localMessage = cnbPlacement.placementUnavailableReason();
            localMessageError = true;
            return;
        }
        cnbPlacement.start(value.outputFile());
        client.setScreen(null);
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 1) + "…";
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static String usefulMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    @Override
    public void removed() {
        persistFields();
        closeSubscription();
    }

    @Override
    public void close() {
        persistFields();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void closeSubscription() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
            subscription = null;
        }
        if (modelSubscription != null) {
            try {
                modelSubscription.close();
            } catch (Exception ignored) {
            }
            modelSubscription = null;
        }
        if (textModelSubscription != null) {
            try {
                textModelSubscription.close();
            } catch (Exception ignored) {
            }
            textModelSubscription = null;
        }
        if (runtimeSubscription != null) {
            try {
                runtimeSubscription.close();
            } catch (Exception ignored) {
            }
            runtimeSubscription = null;
        }
    }

    private enum MapEntryComparator
            implements Comparator<java.util.Map.Entry<String, Integer>> {
        INSTANCE;

        @Override
        public int compare(
                java.util.Map.Entry<String, Integer> left,
                java.util.Map.Entry<String, Integer> right
        ) {
            int count = Integer.compare(right.getValue(), left.getValue());
            return count != 0 ? count : left.getKey().compareTo(right.getKey());
        }
    }

    private record StatusLine(Text text, int color) {
    }
}
