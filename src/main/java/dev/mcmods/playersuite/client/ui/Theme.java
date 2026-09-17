package dev.mcmods.playersuite.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 全套界面共用的绘制工具：不依赖任何材质文件，纯矩形绘制，
 * 因此不会出现缺材质黑框，也方便整合包作者用资源包覆盖风格。
 *
 * <p>坐标约定：调用前已由 {@code AbstractContainerScreen} 平移到界面左上角，
 * 所以这里的 x/y 都是“相对面板”的坐标（与 renderLabels 一致）。
 */
public final class Theme {
    public static final int COLOR_BG = 0xFF101218;
    public static final int COLOR_PANEL = 0xFF232630;
    public static final int COLOR_HEADER = 0xFF2E3240;
    public static final int COLOR_SLOT = 0xFF171A21;
    public static final int COLOR_GRID = 0xFF333746;
    public static final int COLOR_TEXT = 0xFFDCDFE8;
    public static final int COLOR_TEXT_DIM = 0xFF9BA1B2;
    public static final int COLOR_OK = 0xFF8FE08F;
    public static final int COLOR_WARN = 0xFFE08F8F;
    public static final int COLOR_HIGHLIGHT = 0xFF4A5D8A;
    public static final int PITCH = 18;
    public static final int GRID_LEFT = 8;

    private Theme() {
    }

    /** 整块面板底色 + 外框。 */
    public static void panel(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, COLOR_PANEL);
        graphics.fill(0, 0, width, 1, COLOR_GRID);
        graphics.fill(0, height - 1, width, height, COLOR_GRID);
        graphics.fill(0, 0, 1, height, COLOR_GRID);
        graphics.fill(width - 1, 0, width, height, COLOR_GRID);
    }

    /** 顶部标题条。 */
    public static void header(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, COLOR_HEADER);
        graphics.fill(0, height - 1, width, height, COLOR_GRID);
    }

    /** 一行槽位背景（含格子线）。 */
    public static void slotRow(GuiGraphics graphics, int top, int rows, int columns) {
        int left = GRID_LEFT;
        int width = columns * PITCH;
        graphics.fill(left, top, left + width, top + rows * PITCH, COLOR_SLOT);
        for (int row = 0; row <= rows; row++) {
            graphics.fill(left, top + row * PITCH, left + width, top + row * PITCH + 1, COLOR_GRID);
        }
        for (int col = 0; col <= columns; col++) {
            graphics.fill(left + col * PITCH, top, left + col * PITCH + 1, top + rows * PITCH, COLOR_GRID);
        }
    }

    /** 高亮框（选中行）。 */
    public static void highlight(GuiGraphics graphics, int left, int top, int width, int height) {
        graphics.fill(left, top, left + width, top + 1, COLOR_HIGHLIGHT);
        graphics.fill(left, top + height - 1, left + width, top + height, COLOR_HIGHLIGHT);
        graphics.fill(left, top, left + 1, top + height, COLOR_HIGHLIGHT);
        graphics.fill(left + width - 1, top, left + width, top + height, COLOR_HIGHLIGHT);
    }

    /** 分隔线。 */
    public static void line(GuiGraphics graphics, int left, int top, int width) {
        graphics.fill(left, top, left + width, top + 1, COLOR_GRID);
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** 左上对齐文字。 */
    public static int text(GuiGraphics graphics, Component text, int x, int y, int color) {
        return graphics.drawString(font(), text, x, y, color, false);
    }

    /** 左上对齐字符串。 */
    public static int text(GuiGraphics graphics, String text, int x, int y, int color) {
        return graphics.drawString(font(), text, x, y, color, false);
    }

    /** 右对齐文字（贴面板右侧留 8 像素）。 */
    public static void rightText(GuiGraphics graphics, Component text, int panelWidth, int y, int color) {
        Font font = font();
        int x = panelWidth - GRID_LEFT - font.width(text);
        graphics.drawString(font, text, Math.max(GRID_LEFT, x), y, color, false);
    }

    public static void rightText(GuiGraphics graphics, String text, int panelWidth, int y, int color) {
        Font font = font();
        int x = panelWidth - GRID_LEFT - font.width(text);
        graphics.drawString(font, text, Math.max(GRID_LEFT, x), y, color, false);
    }
}
