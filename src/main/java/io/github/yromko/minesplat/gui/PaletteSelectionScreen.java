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
import java.util.List;
import java.util.Locale;

final class PaletteSelectionScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 8;

    private final Screen parent;
    private final MineSplatConfig config;
    private final BlockPalette palette;
    private final CustomPaletteStore store;
    private final List<MineSplatButton> choiceButtons = new ArrayList<>();
    private List<Choice> choices = List.of();
    private TextFieldWidget search;
    private ButtonWidget previous;
    private ButtonWidget next;
    private ButtonWidget edit;
    private ButtonWidget duplicate;
    private MineSplatButton delete;
    private int page;
    private int pageCount = 1;
    private int pageSize = MAX_PAGE_SIZE;
    private int panelWidth;
    private int left;
    private String deletePendingId;
    private String message;
    private boolean messageError;
    private String searchQuery = "";

    PaletteSelectionScreen(
            Screen parent,
            MineSplatConfig config,
            BlockPalette palette,
            CustomPaletteStore store
    ) {
        super(Text.translatable("minesplat.palettes.title"));
        this.parent = parent;
        this.config = config;
        this.palette = palette;
        this.store = store;
    }

    @Override
    protected void init() {
        choiceButtons.clear();
        pageSize = Math.max(3, Math.min(MAX_PAGE_SIZE, (height - 166) / 22));
        panelWidth = Math.min(420, width - 32);
        left = (width - panelWidth) / 2;
        choices = choices();
        if (!config.customPaletteId().isBlank() && selectedCustom().isEmpty()) {
            config.customPaletteId("");
            saveConfig();
        }

        search = new TextFieldWidget(
                textRenderer,
                left,
                38,
                panelWidth,
                20,
                Text.translatable("minesplat.palettes.search"));
        search.setPlaceholder(Text.translatable("minesplat.palettes.search"));
        search.setMaxLength(128);
        search.setText(searchQuery);
        search.setChangedListener(value -> {
            searchQuery = value;
            page = 0;
            deletePendingId = null;
            refresh();
        });
        addDrawableChild(search);

        for (int index = 0; index < pageSize; index++) {
            int buttonIndex = index;
            MineSplatButton button = MineSplatButton.choice(
                    left,
                    64 + index * 22,
                    panelWidth,
                    20,
                    Text.empty(),
                    false,
                    ignored -> select(buttonIndex));
            choiceButtons.add(addDrawableChild(button));
        }

        int actionY = height - 52;
        int actionGap = 4;
        int actionWidth = (panelWidth - actionGap * 3) / 4;
        addDrawableChild(MineSplatButton.primary(
                left,
                actionY,
                actionWidth,
                20,
                Text.translatable("minesplat.palettes.new"),
                ignored -> openEditor(null)));
        edit = addDrawableChild(MineSplatButton.secondary(
                left + actionWidth + actionGap,
                actionY,
                actionWidth,
                20,
                Text.translatable("minesplat.palettes.edit"),
                ignored -> selectedCustom().ifPresent(this::openEditor)));
        duplicate = addDrawableChild(MineSplatButton.secondary(
                left + (actionWidth + actionGap) * 2,
                actionY,
                actionWidth,
                20,
                Text.translatable("minesplat.palettes.duplicate"),
                ignored -> duplicateSelected()));
        delete = addDrawableChild(MineSplatButton.danger(
                left + (actionWidth + actionGap) * 3,
                actionY,
                panelWidth - (actionWidth + actionGap) * 3,
                20,
                Text.translatable("minesplat.palettes.delete"),
                ignored -> deleteSelected()));

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
                left + panelWidth / 2 - 50,
                footerY,
                100,
                20,
                Text.translatable("gui.done"),
                ignored -> close()));
        refresh();
    }

    private List<Choice> choices() {
        List<Choice> result = new ArrayList<>();
        for (PaletteProfile profile : PaletteProfile.values()) {
            result.add(Choice.builtIn(profile));
        }
        store.list().forEach(value -> result.add(Choice.custom(value)));
        return List.copyOf(result);
    }

    private void select(int buttonIndex) {
        List<Choice> filtered = filtered();
        int index = page * pageSize + buttonIndex;
        if (index >= filtered.size()) {
            return;
        }
        Choice choice = filtered.get(index);
        if (choice.custom() == null) {
            config.paletteProfile(choice.profile());
        } else {
            config.selectCustomPalette(
                    choice.custom().id(), choice.custom().baseCategory());
        }
        deletePendingId = null;
        message = null;
        saveConfig();
        refresh();
    }

    private void refresh() {
        if (search == null) {
            return;
        }
        List<Choice> filtered = filtered();
        pageCount = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        for (int index = 0; index < choiceButtons.size(); index++) {
            MineSplatButton button = choiceButtons.get(index);
            int resultIndex = page * pageSize + index;
            button.visible = resultIndex < filtered.size();
            if (button.visible) {
                Choice choice = filtered.get(resultIndex);
                button.setMessage(choice.label());
                button.setSelected(choice.selected(config));
            }
        }
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
        boolean customSelected = selectedCustom().isPresent();
        edit.active = customSelected;
        duplicate.active = customSelected;
        delete.active = customSelected;
        delete.setMessage(Text.translatable(deletePendingId == null
                ? "minesplat.palettes.delete"
                : "minesplat.palettes.delete_confirm"));
    }

    private List<Choice> filtered() {
        String needle = search == null
                ? ""
                : search.getText().strip().toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(value -> needle.isEmpty()
                        || value.searchText().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private java.util.Optional<CustomPalette> selectedCustom() {
        return store.find(config.customPaletteId());
    }

    private void openEditor(CustomPalette custom) {
        deletePendingId = null;
        if (client != null) {
            client.setScreen(new CustomPaletteEditorScreen(
                    this, config, palette, store, custom));
        }
    }

    private void duplicateSelected() {
        selectedCustom().ifPresent(source -> {
            try {
                String copyName = Text.translatable(
                        "minesplat.palettes.copy_name", source.name()).getString();
                if (copyName.length() > CustomPalette.MAX_NAME_LENGTH) {
                    copyName = copyName.substring(0, CustomPalette.MAX_NAME_LENGTH).strip();
                }
                CustomPalette copy = store.duplicate(source, copyName);
                config.selectCustomPalette(copy.id(), copy.baseCategory());
                saveConfig();
                message = null;
                messageError = false;
                choices = choices();
            } catch (IOException | IllegalArgumentException exception) {
                message = usefulMessage(exception);
                messageError = true;
            }
            deletePendingId = null;
            refresh();
        });
    }

    private void deleteSelected() {
        selectedCustom().ifPresent(custom -> {
            if (!custom.id().equals(deletePendingId)) {
                deletePendingId = custom.id();
                refresh();
                return;
            }
            try {
                store.delete(custom.id());
                config.paletteProfile(custom.baseCategory());
                saveConfig();
                message = null;
                messageError = false;
                choices = choices();
            } catch (IOException exception) {
                message = usefulMessage(exception);
                messageError = true;
            }
            deletePendingId = null;
            refresh();
        });
    }

    private void saveConfig() {
        try {
            config.save();
        } catch (IOException exception) {
            message = usefulMessage(exception);
            messageError = true;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MineSplatPanel.render(context, left, panelWidth, height);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xffffffff);
        if (message != null) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    message,
                    width / 2,
                    height - 66,
                    messageError ? 0xffff6b6b : 0xff6fe0cf);
        } else {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("minesplat.palettes.page", page + 1, pageCount),
                    width / 2,
                    height - 66,
                    0xffa0a0a0);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private static String usefulMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private record Choice(PaletteProfile profile, CustomPalette custom, Text label) {
        private static Choice builtIn(PaletteProfile profile) {
            return new Choice(
                    profile,
                    null,
                    Text.translatable("minesplat.palette." + profile.id()));
        }

        private static Choice custom(CustomPalette custom) {
            return new Choice(null, custom, Text.literal(custom.name()));
        }

        private boolean selected(MineSplatConfig config) {
            return custom == null
                    ? config.customPaletteId().isBlank() && profile == config.paletteProfile()
                    : custom.id().equals(config.customPaletteId());
        }

        private String searchText() {
            return custom == null ? profile.id() + " " + label.getString() : custom.name();
        }
    }
}
