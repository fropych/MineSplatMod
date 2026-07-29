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
    private static final int PAGE_SIZE = 8;

    private final Screen parent;
    private final MineSplatConfig config;
    private final List<String> blockIds;
    private final Set<String> blacklisted;
    private final List<ButtonWidget> blockButtons = new ArrayList<>();
    private TextFieldWidget search;
    private ButtonWidget previous;
    private ButtonWidget next;
    private int page;
    private int pageCount = 1;

    BlacklistScreen(Screen parent, MineSplatConfig config, BlockPalette palette) {
        super(Text.translatable("minesplat.blacklist"));
        this.parent = parent;
        this.config = config;
        this.blockIds = palette.blockIds().stream().sorted().toList();
        this.blacklisted = config.blacklistedBlocks();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(460, width - 24);
        int left = (width - panelWidth) / 2;
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

        for (int index = 0; index < PAGE_SIZE; index++) {
            int buttonIndex = index;
            ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> toggle(buttonIndex))
                    .dimensions(left, 64 + index * 22, panelWidth, 20)
                    .build();
            blockButtons.add(addDrawableChild(button));
        }

        previous = addDrawableChild(ButtonWidget.builder(
                        Text.literal("◀"), ignored -> {
                            page = Math.max(0, page - 1);
                            refresh();
                        })
                .dimensions(left, 244, 40, 20).build());
        next = addDrawableChild(ButtonWidget.builder(
                        Text.literal("▶"), ignored -> {
                            page = Math.min(pageCount - 1, page + 1);
                            refresh();
                        })
                .dimensions(left + panelWidth - 40, 244, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.done"), ignored -> close())
                .dimensions(left + panelWidth / 2 - 50, 244, 100, 20).build());
        refresh();
    }

    private void toggle(int buttonIndex) {
        List<String> filtered = filtered();
        int index = page * PAGE_SIZE + buttonIndex;
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
        pageCount = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pageCount - 1);
        for (int index = 0; index < blockButtons.size(); index++) {
            ButtonWidget button = blockButtons.get(index);
            int resultIndex = page * PAGE_SIZE + index;
            button.visible = resultIndex < filtered.size();
            if (button.visible) {
                String block = filtered.get(resultIndex);
                String mark = blacklisted.contains(block) ? "✕ " : "○ ";
                button.setMessage(Text.literal(mark + block));
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
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xffffff);
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                        "minesplat.blacklist.page",
                        page + 1,
                        pageCount,
                        blacklisted.size()),
                width / 2,
                269,
                0xa0a0a0);
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
