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
    private static final int MAX_PAGE_SIZE = 8;

    private final Screen parent;
    private final CnbBlueprintStore store;
    private final CnbPlacementController placement;
    private volatile List<CnbBlueprintStore.BlueprintFile> files = List.of();
    private volatile String message;
    private int page;
    private int pageSize = MAX_PAGE_SIZE;
    private int panelWidth;
    private int left;

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
        pageSize = Math.max(3, Math.min(MAX_PAGE_SIZE, (height - 104) / 24));
        panelWidth = Math.min(420, width - 32);
        left = (width - panelWidth) / 2;
        int rowWidth = panelWidth;
        int start = page * pageSize;
        if (start >= files.size() && page > 0) {
            page = Math.max(0, (files.size() - 1) / pageSize);
            start = page * pageSize;
        }
        int end = Math.min(files.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            CnbBlueprintStore.BlueprintFile file = files.get(index);
            int row = index - start;
            ButtonWidget button = MineSplatButton.secondary(
                    left, 42 + row * 24, rowWidth, 20,
                    label(file), ignored -> place(file));
            button.active = file.valid() && placement.canPlaceNow();
            addDrawableChild(button);
        }
        int half = (panelWidth - 6) / 2;
        ButtonWidget previous = addDrawableChild(MineSplatButton.secondary(
                left, height - 52, half, 20,
                Text.translatable("minesplat.cnb.previous"),
                ignored -> {
                    page = Math.max(0, page - 1);
                    clearAndInit();
                }));
        previous.active = page > 0;
        ButtonWidget next = addDrawableChild(MineSplatButton.secondary(
                left + half + 6, height - 52, half, 20,
                Text.translatable("minesplat.cnb.next"),
                ignored -> {
                    page++;
                    clearAndInit();
                }));
        next.active = (page + 1) * pageSize < files.size();
        int backWidth = Math.min(112, panelWidth);
        addDrawableChild(MineSplatButton.secondary(
                left + (panelWidth - backWidth) / 2,
                height - 28, backWidth, 20,
                Text.translatable("gui.back"), ignored -> close()));
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
        renderPanel(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(
                textRenderer, title, width / 2, 14, 0xffffffff);
        if (message != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer, message, width / 2, 30, 0xffffaa00);
        }
        if (!placement.canPlaceNow()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    placement.placementUnavailableReason(),
                    width / 2, height - 66, 0xffffaa00);
        }
    }

    private void renderPanel(DrawContext context) {
        MineSplatPanel.render(context, left, panelWidth, height);
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
