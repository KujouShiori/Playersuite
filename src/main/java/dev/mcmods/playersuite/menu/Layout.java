package dev.mcmods.playersuite.menu;

/**
 * 功能页面共用的界面几何常量（与 {@code WarehouseLayout} 同源，但支持“内容行 + 可选玩家物品栏”）。
 *
 * <p>坐标都是相对界面面板左上角的像素值；原版槽位是 18x18，一行 9 个共 162 宽，
 * 面板宽 176（左右各留 8 像素），与箱子/熔炉界面一致。
 */
public final class Layout {
    public static final int PANEL_WIDTH = 176;
    public static final int GRID_LEFT = 8;
    public static final int PITCH = 18;
    /** 顶部标题条高度。 */
    public static final int HEADER = 17;
    /** 内容区与玩家物品栏之间的间距（含一行说明文字）。 */
    public static final int GAP = 22;
    /** 底部按钮区高度（两行按钮）。 */
    public static final int FOOTER = 26;

    private Layout() {
    }

    /** 内容区顶部 y。 */
    public static int contentTop() {
        return HEADER + 4;
    }

    /** 玩家物品栏顶部 y（内容区之下）。 */
    public static int inventoryTop(int contentRows) {
        return contentTop() + contentRows * PITCH + GAP;
    }

    /** 底部第一行按钮的 y。 */
    public static int footerRow1(int contentRows, boolean withInventory) {
        int base = withInventory ? inventoryTop(contentRows) + 3 * PITCH + PITCH : contentTop() + contentRows * PITCH + GAP;
        return base + 4;
    }

    /** 底部第二行按钮的 y。 */
    public static int footerRow2(int contentRows, boolean withInventory) {
        return footerRow1(contentRows, withInventory) + 24;
    }

    /** 面板总高度。 */
    public static int panelHeight(int contentRows, boolean withInventory) {
        return footerRow2(contentRows, withInventory) + PITCH + 6;
    }

    /** 第 index 个内容槽的 x（每行 9 个）。 */
    public static int slotX(int indexInRow) {
        return GRID_LEFT + indexInRow * PITCH;
    }

    /** 第 row 行内容槽的 y。 */
    public static int slotY(int row) {
        return contentTop() + row * PITCH;
    }
}
