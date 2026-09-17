package dev.mcmods.playersuite.menu;

/**
 * 仓库界面的布局常量。服务端生成槽位坐标与客户端绘制面板必须使用同一套几何参数，
 * 因此单独抽成一个纯数值的公共类（不引用任何客户端类）。
 */
public final class WarehouseLayout {
    /** 面板宽度，与原版箱子一致。 */
    public static final int PANEL_WIDTH = 176;
    /** 第一格左上角 X。 */
    public static final int GRID_LEFT = 8;
    /** 仓库网格第一行的 Y。 */
    public static final int GRID_TOP = 18;
    /** 槽位间距。 */
    public static final int PITCH = 18;
    /** 仓库网格底部到「物品栏」区域的间距（留出标签位置）。 */
    public static final int PLAYER_LABEL_GAP = 22;
    /** 主物品栏与快捷栏的间距。 */
    public static final int HOTBAR_GAP = 2;
    /** 底部按钮区高度（三行按钮：扩充 / 整理与翻页 / 返回总入口）。 */
    public static final int BUTTON_AREA = 76;

    private WarehouseLayout() {
    }

    public static int slotX(int col) {
        return GRID_LEFT + col * PITCH;
    }

    public static int warehouseSlotY(int rowInPage) {
        return GRID_TOP + rowInPage * PITCH;
    }

    /** 玩家主物品栏第一行的 Y。 */
    public static int playerTop(int rowsOnPage) {
        return GRID_TOP + rowsOnPage * PITCH + PLAYER_LABEL_GAP;
    }

    /** 快捷栏 Y。 */
    public static int hotbarTop(int rowsOnPage) {
        return playerTop(rowsOnPage) + 3 * PITCH + HOTBAR_GAP;
    }

    /** 整个面板高度。 */
    public static int panelHeight(int rowsOnPage) {
        return hotbarTop(rowsOnPage) + PITCH + BUTTON_AREA;
    }

    /** 第一行按钮（扩充）的 Y。 */
    public static int buttonRow1(int rowsOnPage) {
        return panelHeight(rowsOnPage) - BUTTON_AREA + 4;
    }

    /** 第二行按钮（整理 / 翻页）的 Y。 */
    public static int buttonRow2(int rowsOnPage) {
        return buttonRow1(rowsOnPage) + 24;
    }

    /** 第三行按钮（返回总入口）的 Y。 */
    public static int buttonRow3(int rowsOnPage) {
        return buttonRow2(rowsOnPage) + 24;
    }
}
