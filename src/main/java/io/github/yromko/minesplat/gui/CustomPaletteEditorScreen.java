package io.github.yromko.minesplat.gui;

import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.palette.CustomPalette;
import io.github.yromko.minesplat.palette.CustomPaletteStore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CustomPaletteEditorScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 8;

    private final Screen parent;
    private final MineSplatConfig config;
    private final BlockPalette palette;
    private final CustomPaletteStore store;
    private final CustomPalette existing;
    private final List<String> blockIds;
    private final Set<String> selectedBlocks;
    private final List<MineSplatButton> blockButtons = new ArrayList<>();
    private PaletteProfile baseCategory;
    private TextFieldWidget name;
    private TextFieldWidget search;
    private MineSplatButton baseButton;
    private MineSplatButton clearButton;
    private ButtonWidget previous;
    private ButtonWidget next;
    private int page;
    private int pageCount = 1;
    private int pageSize = MAX_PAGE_SIZE;
    private int panelWidth;
    private int left;
    private String message;
    private String draftName;
    private String searchQuery = "";

    CustomPaletteEditorScreen(
            Screen parent,
            MineSplatConfig config,
            BlockPalette palette,
            CustomPaletteStore store,
            CustomPalette existing
    ) {
        super(Text.translatable(existing == null
                ? "minesplat.palette_editor.new_title"
                : "minesplat.palette_editor.edit_title"));
        this.parent = parent;
        this.config = config;
        this.palette = palette;
        this.store = store;
        this.existing = existing;
        this.blockIds = palette.blockIds().stream().sorted().toList();
        this.baseCategory = existing == null
                ? config.paletteProfile()
                : existing.baseCategory();
        this.selectedBlocks = new LinkedHashSet<>(existing == null
                ? palette.blockIds(baseCategory)
                : existing.effectiveBlocks(palette));
        this.draftName = existing == null ? "" : existing.name();
    }

    @Override
    protected void init() {
        blockButtons.clear();
        pageSize = Math.max(3, Math.min(MAX_PAGE_SIZE, (height - 166) / 22));
        panelWidth = Math.min(420, width - 32);
        left = (width - panelWidth) / 2;

        name = new TextFieldWidget(
                textRenderer,
                left,
                38,
                panelWidth,
                20,
                Text.translatable("minesplat.palette_editor.name"));
        name.setPlaceholder(Text.translatable("minesplat.palette_editor.name"));
        name.setMaxLength(CustomPalette.MAX_NAME_LENGTH);
        name.setText(draftName);
        name.setChangedListener(value -> draftName = value);
        addDrawableChild(name);

        int buttonGap = 6;
        int third = (panelWidth - buttonGap * 2) / 3;
        int lastWidth = panelWidth - third * 2 - buttonGap * 2;
        baseButton = addDrawableChild(MineSplatButton.secondary(
                left,
                64,
                third,
                20,
                baseLabel(),
                ignored -> {
                    baseCategory = baseCategory.next();
                    baseButton.setMessage(baseLabel());
                }));
        addDrawableChild(MineSplatButton.secondary(
                left + third + buttonGap,
                64,
                third,
                20,
                Text.translatable("minesplat.palette_editor.reset"),
                ignored -> {
                    selectedBlocks.clear();
                    selectedBlocks.addAll(palette.blockIds(baseCategory));
                    message = null;
                    refresh();
                }));
        clearButton = addDrawableChild(MineSplatButton.danger(
                left + third * 2 + buttonGap * 2,
                64,
                lastWidth,
                20,
                Text.translatable("minesplat.palette_editor.clear"),
                ignored -> {
                    selectedBlocks.clear();
                    message = null;
                    refresh();
                }));

        search = new TextFieldWidget(
                textRenderer,
                left,
                90,
                panelWidth,
                20,
                Text.translatable("minesplat.palette_editor.search"));
        search.setPlaceholder(Text.translatable("minesplat.palette_editor.search"));
        search.setMaxLength(128);
        search.setText(searchQuery);
        search.setChangedListener(value -> {
            searchQuery = value;
            page = 0;
            refresh();
        });
        addDrawableChild(search);

        for (int index = 0; index < pageSize; index++) {
            int buttonIndex = index;
            MineSplatButton button = MineSplatButton.choice(
                    left,
                    116 + index * 22,
                    panelWidth,
                    20,
                    Text.empty(),
                    false,
                    ignored -> toggle(buttonIndex));
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
        int cancelWidth = 76;
        int saveWidth = 92;
        int rowWidth = cancelWidth + 6 + saveWidth;
        int rowLeft = left + (panelWidth - rowWidth) / 2;
        addDrawableChild(MineSplatButton.secondary(
                rowLeft,
                footerY,
                cancelWidth,
                20,
                Text.translatable("gui.cancel"),
                ignored -> close()));
        addDrawableChild(MineSplatButton.primary(
                rowLeft + cancelWidth + 6,
                footerY,
                saveWidth,
                20,
                Text.translatable("minesplat.palette_editor.save"),
                ignored -> save()));
        refresh();
    }

    private void toggle(int buttonIndex) {
        List<String> filtered = filtered();
        int index = page * pageSize + buttonIndex;
        if (index >= filtered.size()) {
            return;
        }
        String block = filtered.get(index);
        if (!selectedBlocks.remove(block)) {
            selectedBlocks.add(block);
        }
        message = null;
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
                button.setMessage(Text.literal(block));
                button.setSelected(selectedBlocks.contains(block));
            }
        }
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
        clearButton.active = !selectedBlocks.isEmpty();
    }

    private List<String> filtered() {
        String needle = search == null
                ? ""
                : search.getText().strip().toLowerCase(Locale.ROOT);
        return blockIds.stream()
                .filter(value -> needle.isEmpty() || value.contains(needle))
                .toList();
    }

    private Text baseLabel() {
        return Text.translatable("minesplat.palette_editor.base")
                .append(": ")
                .append(Text.translatable("minesplat.palette." + baseCategory.id()));
    }

    private void save() {
        if (name.getText().isBlank()) {
            message = Text.translatable("minesplat.palette_editor.name_required").getString();
            return;
        }
        if (selectedBlocks.isEmpty()) {
            message = Text.translatable("minesplat.palette_editor.empty").getString();
            return;
        }
        try {
            CustomPalette custom = existing == null
                    ? CustomPalette.create(
                            name.getText(), baseCategory, selectedBlocks, palette)
                    : existing.withSelection(
                            name.getText(), baseCategory, selectedBlocks, palette);
            store.save(custom);
            config.selectCustomPalette(custom.id(), custom.baseCategory());
            config.save();
            if (client != null) {
                client.setScreen(parent);
            }
        } catch (IOException | IllegalArgumentException exception) {
            message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MineSplatPanel.render(context, left, panelWidth, height);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xffffff);
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("minesplat.palette_editor.name"),
                left,
                28,
                0xa0a0a0);
        Text footer = message == null
                ? Text.translatable(
                        "minesplat.palette_editor.page",
                        page + 1,
                        pageCount,
                        selectedBlocks.size())
                : Text.literal(message);
        context.drawCenteredTextWithShadow(
                textRenderer,
                footer,
                width / 2,
                height - 42,
                message == null ? 0xa0a0a0 : 0xffff6b6b);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
