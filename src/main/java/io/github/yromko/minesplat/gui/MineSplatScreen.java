package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.api.TripoSplatApiClient;
import io.github.yromko.minesplat.client.MineSplatDraft;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.util.FileNames;
import io.github.yromko.minesplat.util.ImageFiles;
import io.github.yromko.minesplat.workflow.GenerationRequest;
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
    private volatile GenerationSnapshot snapshot;
    private AutoCloseable subscription;

    private TextFieldWidget serverUrl;
    private TextFieldWidget schematicName;
    private TextFieldWidget seed;
    private CyclingButtonWidget<VoxelPreset> preset;
    private CyclingButtonWidget<PaletteProfile> paletteProfile;
    private ButtonWidget imageButton;
    private ButtonWidget createButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resumeButton;
    private ButtonWidget finishSessionButton;
    private String localMessage;
    private boolean localMessageError;

    public MineSplatScreen(
            Screen parent,
            MineSplatConfig config,
            BlockPalette palette,
            MineSplatController controller,
            MineSplatDraft draft
    ) {
        super(Text.translatable("minesplat.title"));
        this.parent = parent;
        this.config = config;
        this.palette = palette;
        this.controller = controller;
        this.draft = draft;
        this.snapshot = controller.snapshot();
    }

    @Override
    protected void init() {
        closeSubscription();
        subscription = controller.listen(value -> snapshot = value);
        int panelWidth = Math.min(560, width - 20);
        int left = (width - panelWidth) / 2;
        int gap = 6;
        int testWidth = 116;

        serverUrl = new TextFieldWidget(
                textRenderer, left, 35, panelWidth - testWidth - gap, 20,
                Text.translatable("minesplat.server_url"));
        serverUrl.setMaxLength(2048);
        serverUrl.setText(config.serverUrl());
        serverUrl.setPlaceholder(Text.translatable("minesplat.server_url"));
        addDrawableChild(serverUrl);
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.test_api"), ignored -> testApi())
                .dimensions(left + panelWidth - testWidth, 35, testWidth, 20).build());

        imageButton = addDrawableChild(ButtonWidget.builder(
                        imageLabel(), ignored -> chooseImage())
                .dimensions(left, 61, panelWidth, 20).build());

        int half = (panelWidth - gap) / 2;
        schematicName = new TextFieldWidget(
                textRenderer, left, 87, half, 20, Text.translatable("minesplat.name"));
        schematicName.setMaxLength(128);
        schematicName.setText(draft.schematicName());
        schematicName.setPlaceholder(Text.translatable("minesplat.name"));
        addDrawableChild(schematicName);

        seed = new TextFieldWidget(
                textRenderer, left + half + gap, 87, half, 20,
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
                .build(left, 113, half, 20, Text.translatable("minesplat.preset"),
                        (button, value) -> config.voxelPreset(value)));
        paletteProfile = addDrawableChild(CyclingButtonWidget.<PaletteProfile>builder(
                        value -> Text.literal(value.id()))
                .values(PaletteProfile.values())
                .initially(config.paletteProfile())
                .build(left + half + gap, 113, half, 20,
                        Text.translatable("minesplat.palette"),
                        (button, value) -> config.paletteProfile(value)));

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable(
                                "minesplat.blacklist.count",
                                config.blacklistedBlocks().size()),
                        ignored -> {
                            persistFields();
                            client.setScreen(new BlacklistScreen(this, config, palette));
                        })
                .dimensions(left, 139, panelWidth, 20).build());

        int actionWidth = (panelWidth - gap * 3) / 4;
        createButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.generate"), ignored -> generate())
                .dimensions(left, 165, actionWidth, 20).build());
        cancelButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cancel"), ignored -> controller.cancel())
                .dimensions(left + (actionWidth + gap), 165, actionWidth, 20).build());
        resumeButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.resume_polling"),
                        ignored -> controller.resumePolling())
                .dimensions(left + (actionWidth + gap) * 2, 165, actionWidth, 20).build());
        finishSessionButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.close_session"),
                        ignored -> controller.finishSession())
                .dimensions(left + (actionWidth + gap) * 3, 165, actionWidth, 20).build());
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
        boolean validUrl;
        try {
            TripoSplatApiClient.normalizeBaseUrl(serverUrl.getText());
            validUrl = true;
        } catch (RuntimeException exception) {
            validUrl = false;
        }
        createButton.active = hasWorld && draft.image() != null && validSeed
                && validUrl && !value.state().active();
        cancelButton.active = value.canCancel();
        resumeButton.active = value.pollingPaused();
        finishSessionButton.active = controller.hasSession() && !value.state().active();
        imageButton.setMessage(imageLabel());
    }

    private void testApi() {
        persistFields();
        localMessage = Text.translatable("minesplat.api_testing").getString();
        localMessageError = false;
        controller.testConnection(serverUrl.getText()).whenComplete((info, failure) ->
                client.execute(() -> {
                    if (failure != null) {
                        localMessage = usefulMessage(failure);
                        localMessageError = true;
                    } else {
                        String device = info.selectedDevice() == null
                                ? Text.translatable("minesplat.device_none").getString()
                                : info.selectedDevice().name();
                        localMessage = Text.translatable("minesplat.api_ok", device).getString();
                        localMessageError = false;
                    }
                }));
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
        if (parsedSeed == null || draft.image() == null) {
            return;
        }
        try {
            ImageFiles.validate(draft.image());
            TripoSplatApiClient.normalizeBaseUrl(serverUrl.getText());
            persistFields();
        } catch (Exception exception) {
            localMessage = exception.getMessage();
            localMessageError = true;
            return;
        }

        String name = FileNames.sanitize(schematicName.getText());
        draft.schematicName(name);
        if (controller.canReuseGeneration(serverUrl.getText(), draft.image(), parsedSeed)) {
            controller.rebuildOrRevoxelize(
                    name,
                    preset.getValue(),
                    paletteProfile.getValue(),
                    config.blacklistedBlocks());
        } else {
            controller.start(new GenerationRequest(
                    serverUrl.getText(),
                    draft.image(),
                    name,
                    parsedSeed,
                    preset.getValue(),
                    paletteProfile.getValue(),
                    config.blacklistedBlocks()));
        }
        localMessage = null;
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
        config.serverUrl(serverUrl.getText());
        Long value = parseSeed();
        if (value != null) {
            config.seed(value);
        }
        config.voxelPreset(preset.getValue());
        config.paletteProfile(paletteProfile.getValue());
        draft.schematicName(schematicName.getText());
        try {
            config.save();
        } catch (IOException exception) {
            localMessage = exception.getMessage();
            localMessageError = true;
        }
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
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xffffff);

        GenerationSnapshot value = snapshot;
        int panelWidth = Math.min(560, width - 20);
        int left = (width - panelWidth) / 2;
        renderStepper(context, left, 196, panelWidth, value.state());

        int textY = 216;
        Text stateText = Text.translatable(
                "minesplat.state." + value.state().name().toLowerCase(Locale.ROOT));
        context.drawCenteredTextWithShadow(
                textRenderer, stateText, width / 2, textY,
                value.state() == GenerationState.FAILED ? 0xff5555 : 0xffffff);
        textY += 13;

        if (client.world == null) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.world_required"),
                    width / 2, textY, 0xffaa00);
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
                    textRenderer,
                    Text.translatable(
                            "minesplat.result",
                            value.width(), value.height(), value.depth(), value.blockCount()),
                    width / 2, textY, 0x55ff55);
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

    private void renderStepper(
            DrawContext context,
            int left,
            int y,
            int panelWidth,
            GenerationState current
    ) {
        int currentIndex = stepIndex(current);
        int stepWidth = panelWidth / STEPS.size();
        for (int index = 0; index < STEPS.size(); index++) {
            GenerationState step = STEPS.get(index);
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

    private static int stepIndex(GenerationState state) {
        return switch (state) {
            case UPLOADING -> 0;
            case GENERATION_QUEUED, GENERATION_RUNNING -> 1;
            case VOXELIZATION_QUEUED, VOXELIZATION_RUNNING -> 2;
            case DOWNLOADING -> 3;
            case CONVERTING -> 4;
            case SAVING -> 5;
            case PLACING -> 6;
            case SUCCEEDED -> STEPS.size();
            default -> -1;
        };
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 1) + "…";
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
}
