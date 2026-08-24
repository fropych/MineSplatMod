package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.api.ApiModels.Device;
import io.github.yromko.minesplat.config.GenerationPreset;
import io.github.yromko.minesplat.config.InferenceMode;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.inference.InferenceTarget;
import io.github.yromko.minesplat.inference.LocalModelManager;
import io.github.yromko.minesplat.inference.LocalModelSet;
import io.github.yromko.minesplat.inference.LocalModelSnapshot;
import io.github.yromko.minesplat.inference.LocalModelState;
import io.github.yromko.minesplat.inference.LocalRuntimeManager;
import io.github.yromko.minesplat.inference.LocalRuntimeSnapshot;
import io.github.yromko.minesplat.workflow.GenerationSnapshot;
import io.github.yromko.minesplat.workflow.MineSplatController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class MineSplatSettingsScreen extends Screen {
    private final Screen parent;
    private final MineSplatConfig config;
    private final MineSplatController controller;
    private final LocalModelManager localModels;
    private final LocalRuntimeManager localRuntime;

    private volatile GenerationSnapshot generation;
    private volatile LocalModelSnapshot coreModels;
    private volatile LocalModelSnapshot textModels;
    private volatile LocalRuntimeSnapshot runtime;
    private AutoCloseable generationSubscription;
    private AutoCloseable coreSubscription;
    private AutoCloseable textSubscription;
    private AutoCloseable runtimeSubscription;

    private InferenceMode mode;
    private TextFieldWidget targetField;
    private ButtonWidget remoteModeButton;
    private ButtonWidget localModeButton;
    private ButtonWidget folderButton;
    private ButtonWidget deviceButton;
    private ButtonWidget coreModelsButton;
    private ButtonWidget textModelsButton;
    private ButtonWidget testButton;
    private List<ButtonWidget> generationPresetButtons = List.of();
    private List<Device> availableDevices = List.of();
    private String message;
    private boolean messageError;
    private int panelWidth;
    private int left;
    private int contentTop;
    private int inferenceLabelY;
    private int qualityLabelY;
    private int targetLabelY;
    private int statusTop;

    MineSplatSettingsScreen(
            Screen parent,
            MineSplatConfig config,
            MineSplatController controller,
            LocalModelManager localModels,
            LocalRuntimeManager localRuntime
    ) {
        super(Text.translatable("minesplat.settings.title"));
        this.parent = parent;
        this.config = config;
        this.controller = controller;
        this.localModels = localModels;
        this.localRuntime = localRuntime;
        this.generation = controller.snapshot();
        this.coreModels = localModels.snapshot();
        this.textModels = localModels.textSnapshot();
        this.runtime = localRuntime.snapshot();
        this.mode = config.inferenceMode();
    }

    @Override
    protected void init() {
        closeSubscriptions();
        generationSubscription = controller.listen(value -> generation = value);
        coreSubscription = localModels.listen(value -> coreModels = value);
        textSubscription = localModels.listenText(value -> textModels = value);
        runtimeSubscription = localRuntime.listen(value -> runtime = value);
        buildWidgets();
    }

    private void buildWidgets() {
        clearChildren();
        panelWidth = Math.min(420, width - 32);
        left = (width - panelWidth) / 2;
        contentTop = height < 300 ? 43 : 46;
        int gap = height < 300 ? 4 : 6;
        int widgetHeight = height < 300 ? 18 : 20;
        int labelOffset = height < 300 ? 11 : 12;
        int modeWidth = Math.min(126, (panelWidth - gap) / 2);
        int modeRowWidth = modeWidth * 2 + gap;
        int modeLeft = left + (panelWidth - modeRowWidth) / 2;
        inferenceLabelY = contentTop;
        int modeY = inferenceLabelY + labelOffset;

        remoteModeButton = addDrawableChild(MineSplatButton.choice(
                modeLeft,
                modeY,
                modeWidth,
                widgetHeight,
                Text.translatable("minesplat.inference.remote"),
                mode == InferenceMode.REMOTE,
                ignored -> switchMode(InferenceMode.REMOTE)));
        localModeButton = addDrawableChild(MineSplatButton.choice(
                modeLeft + modeWidth + gap,
                modeY,
                modeWidth,
                widgetHeight,
                Text.translatable("minesplat.inference.local"),
                mode == InferenceMode.LOCAL,
                ignored -> switchMode(InferenceMode.LOCAL)));

        int presetWidth = Math.min(84, (panelWidth - gap * 2) / 3);
        int presetRowWidth = presetWidth * 3 + gap * 2;
        int presetLeft = left + (panelWidth - presetRowWidth) / 2;
        qualityLabelY = modeY + widgetHeight + gap;
        int presetY = qualityLabelY + labelOffset;
        generationPresetButtons = List.of(
                addDrawableChild(MineSplatButton.choice(
                        presetLeft, presetY, presetWidth, widgetHeight,
                        Text.translatable("minesplat.generation.base"),
                        config.generationPreset() == GenerationPreset.BASE,
                        ignored -> selectGenerationPreset(GenerationPreset.BASE))),
                addDrawableChild(MineSplatButton.choice(
                        presetLeft + presetWidth + gap, presetY, presetWidth, widgetHeight,
                        Text.translatable("minesplat.generation.high"),
                        config.generationPreset() == GenerationPreset.HIGH,
                        ignored -> selectGenerationPreset(GenerationPreset.HIGH))),
                addDrawableChild(MineSplatButton.choice(
                        presetLeft + (presetWidth + gap) * 2,
                        presetY, presetWidth, widgetHeight,
                        Text.translatable("minesplat.generation.xhigh"),
                        config.generationPreset() == GenerationPreset.XHIGH,
                        ignored -> selectGenerationPreset(GenerationPreset.XHIGH))));

        int targetY = presetY + widgetHeight + gap + 12;
        targetLabelY = targetY - 12;
        int targetRowWidth = Math.min(360, panelWidth);
        int targetLeft = left + (panelWidth - targetRowWidth) / 2;
        int folderWidth = mode == InferenceMode.LOCAL ? 28 : 0;
        targetField = new TextFieldWidget(
                textRenderer,
                targetLeft,
                targetY,
                targetRowWidth - (folderWidth == 0 ? 0 : folderWidth + gap),
                widgetHeight,
                Text.translatable(mode == InferenceMode.LOCAL
                        ? "minesplat.models.directory" : "minesplat.server_url"));
        targetField.setMaxLength(2048);
        targetField.setText(mode == InferenceMode.LOCAL
                ? localModels.directory().toString() : config.serverUrl());
        targetField.setPlaceholder(Text.translatable(mode == InferenceMode.LOCAL
                ? "minesplat.models.directory" : "minesplat.server_url"));
        addDrawableChild(targetField);

        if (mode == InferenceMode.LOCAL) {
            folderButton = addDrawableChild(MineSplatButton.secondary(
                    targetLeft + targetRowWidth - folderWidth,
                    targetY, folderWidth, widgetHeight,
                    Text.literal("…"), ignored -> chooseModelDirectory()));
            folderButton.setTooltip(Tooltip.of(
                    Text.translatable("minesplat.models.choose_directory")));

            int deviceY = targetY + widgetHeight + gap;
            int deviceWidth = Math.min(300, panelWidth);
            deviceButton = addDrawableChild(MineSplatButton.secondary(
                    left + (panelWidth - deviceWidth) / 2,
                    deviceY, deviceWidth, widgetHeight,
                    localDeviceLabel(), ignored -> cycleLocalDevice()));

            int modelsY = deviceY + widgetHeight + gap;
            int modelsRowWidth = Math.min(360, panelWidth);
            int modelsLeft = left + (panelWidth - modelsRowWidth) / 2;
            int half = (modelsRowWidth - gap) / 2;
            coreModelsButton = addDrawableChild(MineSplatButton.secondary(
                    modelsLeft, modelsY, half, widgetHeight,
                    Text.empty(), ignored -> modelAction(LocalModelSet.CORE)));
            textModelsButton = addDrawableChild(MineSplatButton.secondary(
                    modelsLeft + half + gap, modelsY, half, widgetHeight,
                    Text.empty(), ignored -> modelAction(LocalModelSet.TEXT)));

            int testWidth = Math.min(176, panelWidth);
            int testY = modelsY + widgetHeight + gap;
            testButton = addDrawableChild(MineSplatButton.primary(
                    left + (panelWidth - testWidth) / 2,
                    testY, testWidth, widgetHeight,
                    Text.translatable("minesplat.local.test"), ignored -> testTarget()));
            statusTop = testY + widgetHeight + gap + 4;
        } else {
            int testWidth = Math.min(176, panelWidth);
            int testY = targetY + widgetHeight + gap;
            testButton = addDrawableChild(MineSplatButton.primary(
                    left + (panelWidth - testWidth) / 2,
                    testY, testWidth, widgetHeight,
                    Text.translatable("minesplat.test_api"), ignored -> testTarget()));
            statusTop = testY + widgetHeight + gap + 4;
        }

        int backWidth = Math.min(112, panelWidth);
        addDrawableChild(MineSplatButton.secondary(
                left + (panelWidth - backWidth) / 2,
                height - 28, backWidth, 20,
                Text.translatable("gui.back"), ignored -> close()));
        updateControls();
    }

    @Override
    public void tick() {
        super.tick();
        updateControls();
    }

    private void updateControls() {
        if (targetField == null) {
            return;
        }
        boolean operationActive = generation.state().active();
        boolean changingModels = localModels.installing();
        remoteModeButton.active = !operationActive && !changingModels;
        localModeButton.active = !operationActive && !changingModels;
        generationPresetButtons.forEach(
                button -> button.active = !operationActive && !changingModels);
        targetField.active = !operationActive && !changingModels;
        testButton.active = !operationActive
                && (mode == InferenceMode.REMOTE
                || (coreModels.state() == LocalModelState.READY && !changingModels));

        if (mode != InferenceMode.LOCAL) {
            return;
        }
        folderButton.active = !operationActive && !changingModels;
        deviceButton.active = !operationActive
                && coreModels.state() == LocalModelState.READY
                && !changingModels;
        deviceButton.setMessage(localDeviceLabel());
        updateModelButton(coreModelsButton, LocalModelSet.CORE, coreModels, operationActive);
        updateModelButton(textModelsButton, LocalModelSet.TEXT, textModels, operationActive);
    }

    private void updateModelButton(
            ButtonWidget button,
            LocalModelSet set,
            LocalModelSnapshot snapshot,
            boolean operationActive
    ) {
        boolean activeInstall = localModels.installing() && localModels.activeSet() == set;
        if (activeInstall) {
            button.setMessage(Text.translatable("minesplat.models.cancel"));
            button.active = true;
            return;
        }
        String key = set == LocalModelSet.CORE ? "core" : "text";
        button.setMessage(Text.translatable(snapshot.state() == LocalModelState.READY
                ? "minesplat.models." + key + ".ready"
                : "minesplat.models." + key + ".install"));
        button.active = localRuntime.supported()
                && snapshot.state() != LocalModelState.CHECKING
                && snapshot.state() != LocalModelState.READY
                && !localModels.installing()
                && !operationActive;
    }

    private void switchMode(InferenceMode next) {
        if (mode == next) {
            return;
        }
        storeTargetField();
        if (controller.hasSession() && !generation.state().active()) {
            controller.finishSession();
        }
        localRuntime.stop();
        mode = next;
        config.inferenceMode(next);
        saveConfig();
        message = null;
        buildWidgets();
    }

    private void selectGenerationPreset(GenerationPreset value) {
        if (config.generationPreset() == value) {
            return;
        }
        storeTargetField();
        config.generationPreset(value);
        saveConfig();
        buildWidgets();
    }

    private void modelAction(LocalModelSet set) {
        if (localModels.installing() && localModels.activeSet() == set) {
            localModels.cancelInstall();
            return;
        }
        syncModelDirectory().whenComplete((ignored, failure) -> {
            if (failure != null) {
                showFailure(failure);
                return;
            }
            client.execute(() -> {
                message = null;
                messageError = false;
                localRuntime.stop();
                localModels.install(set);
            });
        });
    }

    private void testTarget() {
        if (mode == InferenceMode.LOCAL) {
            syncModelDirectory().whenComplete((ready, failure) -> {
                if (failure != null) {
                    showFailure(failure);
                } else if (!ready) {
                    client.execute(() -> {
                        message = Text.translatable(
                                "minesplat.local.status.models_missing").getString();
                        messageError = true;
                    });
                } else {
                    client.execute(() -> testConnection(
                            new InferenceTarget.Local(config.localDeviceIndex())));
                }
            });
            return;
        }
        try {
            InferenceTarget.Remote target = new InferenceTarget.Remote(targetField.getText());
            targetField.setText(target.baseUrl());
            config.serverUrl(target.baseUrl());
            saveConfig();
            testConnection(target);
        } catch (RuntimeException exception) {
            message = usefulMessage(exception);
            messageError = true;
        }
    }

    private void testConnection(InferenceTarget target) {
        message = Text.translatable("minesplat.api_testing").getString();
        messageError = false;
        controller.testConnection(target).whenComplete((info, failure) ->
                client.execute(() -> {
                    if (failure != null) {
                        message = usefulMessage(failure);
                        messageError = true;
                        return;
                    }
                    availableDevices = info.devices();
                    String device = info.selectedDevice() == null
                            ? Text.translatable("minesplat.device_none").getString()
                            : info.selectedDevice().name();
                    message = Text.translatable("minesplat.api_ok", device).getString();
                    messageError = false;
                }));
    }

    private CompletableFuture<Boolean> syncModelDirectory() {
        try {
            Path entered = Path.of(targetField.getText()).toAbsolutePath().normalize();
            config.localModelDirectory(entered.toString());
            saveConfig();
            if (entered.equals(localModels.directory())) {
                return CompletableFuture.completedFuture(localModels.ready());
            }
            if (controller.hasSession() && !generation.state().active()) {
                controller.finishSession();
            }
            localRuntime.stop();
            return localModels.setDirectory(entered);
        } catch (RuntimeException exception) {
            message = usefulMessage(exception);
            messageError = true;
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
                    targetField.setText(
                            Path.of(selected).toAbsolutePath().normalize().toString());
                    syncModelDirectory();
                });
            }
        }, "MineSplat model directory picker");
        picker.setDaemon(true);
        picker.start();
    }

    private void cycleLocalDevice() {
        if (availableDevices.isEmpty()) {
            testTarget();
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
        if (controller.hasSession() && !generation.state().active()) {
            controller.finishSession();
        }
        localRuntime.stop();
        config.localDeviceIndex(next.index());
        saveConfig();
    }

    private Text localDeviceLabel() {
        int index = config.localDeviceIndex();
        String name = availableDevices.stream()
                .filter(value -> value.index() == index)
                .map(Device::name)
                .findFirst()
                .orElse(runtime.device() == null
                        ? "Vulkan device " + index : runtime.device());
        return Text.translatable("minesplat.local.device", index, name);
    }

    private void storeTargetField() {
        if (targetField == null) {
            return;
        }
        if (mode == InferenceMode.LOCAL) {
            config.localModelDirectory(targetField.getText());
        } else {
            config.serverUrl(targetField.getText());
        }
    }

    private StatusLine currentStatus() {
        if (message != null) {
            return new StatusLine(Text.literal(message), messageError ? 0xffff5555 : 0xff55ff55);
        }
        if (mode != InferenceMode.LOCAL) {
            return new StatusLine(
                    Text.translatable("minesplat.settings.remote.help"), 0xffa0a0a0);
        }
        if (!localRuntime.supported()) {
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.unsupported", localRuntime.platform()), 0xffff5555);
        }
        StatusLine core = modelStatus(LocalModelSet.CORE, coreModels);
        if (core != null) {
            return core;
        }
        StatusLine text = modelStatus(LocalModelSet.TEXT, textModels);
        if (text != null) {
            return text;
        }
        if (runtime.error() != null) {
            return new StatusLine(Text.literal(runtime.error()), 0xffff5555);
        }
        if (runtime.device() != null) {
            return new StatusLine(Text.translatable(
                    "minesplat.local.status.runtime_ready", runtime.device()), 0xff55ff55);
        }
        return new StatusLine(Text.translatable(
                "minesplat.settings.local.ready"), 0xff55ff55);
    }

    private StatusLine modelStatus(LocalModelSet set, LocalModelSnapshot snapshot) {
        String name = Text.translatable("minesplat.models." + set.id()).getString();
        return switch (snapshot.state()) {
            case CHECKING -> new StatusLine(Text.translatable(
                    "minesplat.local.status.models_checking_set", name), 0xffffff55);
            case DOWNLOADING -> new StatusLine(Text.translatable(
                    "minesplat.local.status.models_downloading_set",
                    name,
                    (int) Math.round(snapshot.progress() * 100.0),
                    snapshot.currentFile() == null ? "models" : snapshot.currentFile()),
                    0xffffff55);
            case CONVERTING -> new StatusLine(Text.translatable(
                    "minesplat.local.status.models_converting", name), 0xffffff55);
            case MISSING -> set == LocalModelSet.CORE
                    ? new StatusLine(Text.translatable(
                    "minesplat.local.status.models_missing_set", name), 0xffffaa00)
                    : null;
            case FAILED -> new StatusLine(Text.literal(
                    snapshot.error() == null ? "Model check failed" : snapshot.error()), 0xffff5555);
            case READY -> null;
        };
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderPanel(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xffffffff);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.settings.subtitle"),
                width / 2,
                27,
                0xffa0a0a0);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.inference.type"),
                width / 2,
                inferenceLabelY,
                0xffa0a0a0);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.generation.quality"),
                width / 2,
                qualityLabelY,
                0xffa0a0a0);
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable(mode == InferenceMode.LOCAL
                        ? "minesplat.models.directory" : "minesplat.server_url"),
                targetField.getX(),
                targetLabelY,
                0xffa0a0a0);

        StatusLine status = currentStatus();
        int statusY = Math.min(height - 48, statusTop);
        context.drawWrappedTextWithShadow(
                textRenderer,
                status.text(),
                left,
                statusY,
                panelWidth,
                status.color());
    }

    private void renderPanel(DrawContext context) {
        MineSplatPanel.render(context, left, panelWidth, height);
    }

    @Override
    public void removed() {
        storeTargetField();
        saveConfig();
        closeSubscriptions();
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void saveConfig() {
        try {
            config.save();
        } catch (IOException exception) {
            message = exception.getMessage();
            messageError = true;
        }
    }

    private void showFailure(Throwable throwable) {
        client.execute(() -> {
            message = usefulMessage(throwable);
            messageError = true;
        });
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
