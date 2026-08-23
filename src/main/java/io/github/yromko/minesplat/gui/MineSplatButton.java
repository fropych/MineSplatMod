package io.github.yromko.minesplat.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Shared flat button style for every MineSplat screen. */
final class MineSplatButton extends ButtonWidget {
    enum Tone {
        SECONDARY,
        PRIMARY,
        DANGER
    }

    private static final int SECONDARY_BACKGROUND = 0xe0161c22;
    private static final int SECONDARY_HOVER = 0xf0263039;
    private static final int SECONDARY_BORDER = 0xff46535e;
    private static final int SECONDARY_HOVER_BORDER = 0xff7f909c;

    private static final int PRIMARY_TOP = 0xf0366e69;
    private static final int PRIMARY_BOTTOM = 0xf0244c4a;
    private static final int PRIMARY_HOVER_TOP = 0xff43847d;
    private static final int PRIMARY_HOVER_BOTTOM = 0xff2d5d58;
    private static final int PRIMARY_BORDER = 0xff6fe0cf;

    private static final int DANGER_BACKGROUND = 0xe052292d;
    private static final int DANGER_HOVER = 0xf06c343a;
    private static final int DANGER_BORDER = 0xffc46a70;
    private static final int DANGER_HOVER_BORDER = 0xffff9298;

    private static final int DISABLED_BACKGROUND = 0x9014191e;
    private static final int DISABLED_BORDER = 0xff343c43;

    private final Tone tone;
    private net.minecraft.text.Text label;
    private boolean selected;

    MineSplatButton(
            int x,
            int y,
            int width,
            int height,
            net.minecraft.text.Text label,
            Tone tone,
            boolean selected,
            PressAction onPress
    ) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.tone = tone;
        this.label = label;
        this.selected = selected;
        refreshDisplayedMessage();
    }

    static MineSplatButton secondary(
            int x, int y, int width, int height,
            net.minecraft.text.Text label, PressAction onPress) {
        return new MineSplatButton(
                x, y, width, height, label, Tone.SECONDARY, false, onPress);
    }

    static MineSplatButton primary(
            int x, int y, int width, int height,
            net.minecraft.text.Text label, PressAction onPress) {
        return new MineSplatButton(
                x, y, width, height, label, Tone.PRIMARY, false, onPress);
    }

    static MineSplatButton danger(
            int x, int y, int width, int height,
            net.minecraft.text.Text label, PressAction onPress) {
        return new MineSplatButton(
                x, y, width, height, label, Tone.DANGER, false, onPress);
    }

    static MineSplatButton choice(
            int x,
            int y,
            int width,
            int height,
            net.minecraft.text.Text label,
            boolean selected,
            PressAction onPress
    ) {
        return new MineSplatButton(
                x, y, width, height, label, Tone.SECONDARY, selected, onPress);
    }

    @Override
    public void setMessage(net.minecraft.text.Text message) {
        label = message;
        refreshDisplayedMessage();
    }

    void setSelected(boolean selected) {
        if (this.selected == selected) {
            return;
        }
        this.selected = selected;
        refreshDisplayedMessage();
    }

    private void refreshDisplayedMessage() {
        super.setMessage(selected
                ? net.minecraft.text.Text.literal("✓ ").append(label)
                : label);
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        boolean highlighted = isHovered() || isFocused();
        int border;

        if (!active) {
            context.fill(x, y, x + width, y + height, DISABLED_BACKGROUND);
            border = DISABLED_BORDER;
        } else if (selected || tone == Tone.PRIMARY) {
            context.fillGradient(
                    x,
                    y,
                    x + width,
                    y + height,
                    highlighted ? PRIMARY_HOVER_TOP : PRIMARY_TOP,
                    highlighted ? PRIMARY_HOVER_BOTTOM : PRIMARY_BOTTOM);
            border = PRIMARY_BORDER;
        } else if (tone == Tone.DANGER) {
            context.fill(
                    x,
                    y,
                    x + width,
                    y + height,
                    highlighted ? DANGER_HOVER : DANGER_BACKGROUND);
            border = highlighted ? DANGER_HOVER_BORDER : DANGER_BORDER;
        } else {
            context.fill(
                    x,
                    y,
                    x + width,
                    y + height,
                    highlighted ? SECONDARY_HOVER : SECONDARY_BACKGROUND);
            border = highlighted ? SECONDARY_HOVER_BORDER : SECONDARY_BORDER;
        }

        if (selected && active) {
            context.fill(x, y, x + 3, y + height, PRIMARY_BORDER);
        }
        context.drawStrokedRectangle(x, y, width, height, border);
        context.enableScissor(x + 3, y, x + width - 3, y + height);
        context.drawCenteredTextWithShadow(
                MinecraftClient.getInstance().textRenderer,
                getMessage(),
                x + width / 2,
                y + (height - 8) / 2,
                active ? 0xffffff : 0x7f8992);
        context.disableScissor();
    }
}
