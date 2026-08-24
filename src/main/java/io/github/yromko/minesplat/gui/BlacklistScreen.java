package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.palette.BlockPalette;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BlacklistScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 8;

    private final Screen parent;
    private final MineSplatConfig config;
    private final List<String> blockIds;
    private final Set<String> blacklisted;
    private final List<MineSplatButton> blockButtons = new ArrayList<>();
    private TextFieldWidget search;
    private ButtonWidget previous;
    private ButtonWidget next;
    private int page;
    private int pageCount = 1;
    private int pageSize = MAX_PAGE_SIZE;
    private int panelWidth;
    private int left;

    BlacklistScreen(Screen parent, MineSplatConfig config, BlockPalette palette) {
        super(Text.translatable("minesplat.blacklist"));
        this.parent = parent;
        this.config = config;
        this.blockIds = palette.blockIds().stream().sorted().toList();
        this.blacklisted = config.blacklistedBlocks();
    }

    @Override
    protected void init() {
        blockButtons.clear();
        pageSize = Math.max(3, Math.min(MAX_PAGE_SIZE, (height - 110) / 22));
        panelWidth = Math.min(420, width - 32);
        left = (width - panelWidth) / 2;
        search = new TextFieldWidget(
                textRenderer, left, 38, panelWidth, 20,
                Text.translatable("minesplat.blacklist.search"));
        search.setPlaceholder(Text.translatable("minesplat.blacklist.search"));
        search.setMaxLength(128);
        search.setChangedListener(value -> {
            page = 0;
            refresh();
        });
        addDrawableChild(search);

        for (int index = 0; index < pageSize; index++) {
            int buttonIndex = index;
            MineSplatButton button = MineSplatButton.choice(
                    left, 64 + index * 22, panelWidth, 20,
                    Text.empty(), false, ignored -> toggle(buttonIndex));
            blockButtons.add(addDrawableChild(button));
        }

        int footerY = height - 28;
        previous = addDrawableChild(MineSplatButton.secondary(
                left, footerY, 40, 20, Text.literal("◀"), ignored -> {
                    page = Math.max(0, page - 1);
                    refresh();
                }));
        next = addDrawableChild(MineSplatButton.secondary(
                left + panelWidth - 40, footerY, 40, 20, Text.literal("▶"), ignored -> {
                    page = Math.min(pageCount - 1, page + 1);
                    refresh();
                }));
        addDrawableChild(MineSplatButton.primary(
                left + panelWidth / 2 - 50, footerY, 100, 20,
                Text.translatable("gui.done"), ignored -> close()));
        refresh();
    }

    private void toggle(int buttonIndex) {
        List<String> filtered = filtered();
        int index = page * pageSize + buttonIndex;
        if (index >= filtered.size()) {
            return;
        }
        String block = filtered.get(index);
        if (!blacklisted.remove(block)) {
            blacklisted.add(block);
        }
        refresh();
    }

    private void refresh() {
        if (search == null) {
            return;
        }
        List<String> filtered = filtered();
        pageCount = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        for (int index = 0; index < blockButtons.size(); index++) {
            MineSplatButton button = blockButtons.get(index);
            int resultIndex = page * pageSize + index;
            button.visible = resultIndex < filtered.size();
            if (button.visible) {
                String block = filtered.get(resultIndex);
                button.setSelected(blacklisted.contains(block));
                button.setMessage(Text.literal(block));
            }
        }
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
    }

    private List<String> filtered() {
        String needle = search == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        return blockIds.stream()
                .filter(value -> needle.isEmpty() || value.contains(needle))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderPanel(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xffffffff);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                        "minesplat.blacklist.page",
                        page + 1,
                        pageCount,
                        blacklisted.size()),
                width / 2,
                height - 42,
                0xffa0a0a0);
    }

    private void renderPanel(DrawContext context) {
        MineSplatPanel.render(context, left, panelWidth, height);
    }

    @Override
    public void close() {
        config.blacklistedBlocks(blacklisted);
        try {
            config.save();
        } catch (IOException ignored) {
        }
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
