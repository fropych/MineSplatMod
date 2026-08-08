package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.cnb.CnbBlueprintStore;
import io.github.yromko.minesplat.cnb.CnbPlacementController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class CnbBlueprintLibraryScreen extends Screen {
    private static final int PAGE_SIZE = 8;

    private final Screen parent;
    private final CnbBlueprintStore store;
    private final CnbPlacementController placement;
    private volatile List<CnbBlueprintStore.BlueprintFile> files = List.of();
    private volatile String message;
    private int page;

    public CnbBlueprintLibraryScreen(
            Screen parent,
            CnbBlueprintStore store,
            CnbPlacementController placement
    ) {
        super(Text.translatable("minesplat.cnb.library"));
        this.parent = parent;
        this.store = store;
        this.placement = placement;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(560, width - 20);
        int left = (width - panelWidth) / 2;
        int rowWidth = panelWidth;
        int start = page * PAGE_SIZE;
        int end = Math.min(files.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            CnbBlueprintStore.BlueprintFile file = files.get(index);
            int row = index - start;
            ButtonWidget button = ButtonWidget.builder(
                            label(file),
                            ignored -> place(file))
                    .dimensions(left, 42 + row * 24, rowWidth, 20)
                    .build();
            button.active = file.valid() && placement.canPlaceNow();
            addDrawableChild(button);
        }
        int half = (panelWidth - 6) / 2;
        ButtonWidget previous = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cnb.previous"),
                        ignored -> {
                            page = Math.max(0, page - 1);
                            clearAndInit();
                        })
                .dimensions(left, height - 52, half, 20).build());
        previous.active = page > 0;
        ButtonWidget next = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("minesplat.cnb.next"),
                        ignored -> {
                            page++;
                            clearAndInit();
                        })
                .dimensions(left + half + 6, height - 52, half, 20).build());
        next.active = (page + 1) * PAGE_SIZE < files.size();
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.back"),
                        ignored -> close())
                .dimensions(left, height - 28, panelWidth, 20).build());
        if (files.isEmpty() && message == null) {
            reload();
        }
    }

    private void reload() {
        message = Text.translatable("minesplat.cnb.loading").getString();
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.list();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((loaded, failure) -> client.execute(() -> {
            if (failure != null) {
                message = usefulMessage(failure);
            } else {
                files = loaded;
                message = loaded.isEmpty()
                        ? Text.translatable("minesplat.cnb.empty").getString()
                        : null;
            }
            clearAndInit();
        }));
    }

    private Text label(CnbBlueprintStore.BlueprintFile file) {
        if (!file.valid()) {
            return Text.literal(file.path().getFileName() + " · invalid");
        }
        var blueprint = file.blueprint();
        return Text.translatable(
                "minesplat.cnb.library_entry",
                blueprint.name(),
                blueprint.resolution(),
                blueprint.width(),
                blueprint.height(),
                blueprint.depth(),
                blueprint.voxelCount());
    }

    private void place(CnbBlueprintStore.BlueprintFile file) {
        if (!file.valid() || !placement.canPlaceNow()) {
            message = placement.placementUnavailableReason();
            return;
        }
        placement.start(file.path());
        client.setScreen(null);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(
                textRenderer, title, width / 2, 16, 0xffffff);
        if (message != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, message, width / 2, 30, 0xffaa00);
        }
        if (!placement.canPlaceNow()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    placement.placementUnavailableReason(),
                    width / 2, height - 66, 0xffaa00);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
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
}
