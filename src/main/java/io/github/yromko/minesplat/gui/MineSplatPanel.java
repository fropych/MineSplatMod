package io.github.yromko.minesplat.gui;

import net.minecraft.client.gui.DrawContext;

final class MineSplatPanel {
    private static final int TOP = 4;
    private static final int BOTTOM_MARGIN = 3;
    private static final int HORIZONTAL_PADDING = 10;

    private MineSplatPanel() {
    }

    static void render(DrawContext context, int contentLeft, int contentWidth, int screenHeight) {
        int panelLeft = contentLeft - HORIZONTAL_PADDING;
        int panelRight = contentLeft + contentWidth + HORIZONTAL_PADDING;
        int panelBottom = screenHeight - BOTTOM_MARGIN;
        context.fillGradient(
                panelLeft, TOP, panelRight, panelBottom,
                0xe0141920, 0xe00c1015);
        context.drawStrokedRectangle(
                panelLeft, TOP,
                panelRight - panelLeft,
                panelBottom - TOP,
                0xff3d4a55);
        context.fill(
                panelLeft + 1, TOP + 1,
                panelRight - 1, TOP + 3,
                0xff4bb8aa);
    }
}
