package dev.mcmods.playersuite.client;

import dev.mcmods.playersuite.client.ui.CursorKeeper;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.WarehouseLayout;
import dev.mcmods.playersuite.menu.WarehouseMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

/**
 * 个人仓库界面。
 *
 * <p>界面完全用矩形绘制（不依赖任何材质资源），因此不会出现缺材质黑框问题；
 * 每页最多 6 行仓库（等价原版大箱子），更多容量时通过底部按钮翻页。
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {
    // 配色
    private static final int COLOR_OUTER = 0xFF101218;
    private static final int COLOR_PANEL = 0xFF232630;
    private static final int COLOR_HEADER = 0xFF2E3240;
    private static final int COLOR_SLOT_BG = 0xFF171A21;
    private static final int COLOR_GRID = 0xFF333746;
    private static final int COLOR_TEXT = 0xFFDCDFE8;
    private static final int COLOR_TEXT_DIM = 0xFF9BA1B2;
    private static final int COLOR_OK = 0xFF8FE08F;
    private static final int COLOR_WARN = 0xFFE08F8F;

    private static final int SLOT_PITCH = WarehouseLayout.PITCH;

    private final int rowsOnPage;
    private Button upgradeButton;
    private Button sortButton;
    private Button prevButton;
    private Button nextButton;
    private Button hubButton;

    public WarehouseScreen(WarehouseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.rowsOnPage = Math.max(1, menu.rowsOnPage());
        this.imageWidth = WarehouseLayout.PANEL_WIDTH;
        this.imageHeight = WarehouseLayout.panelHeight(this.rowsOnPage);
    }

    @Override
    protected void init() {
        // 1.21.1 中 Screen#init(Minecraft, int, int) 是 final，只能重写无参的 init()；
        // 调用时 this.width/this.height 已赋值，且 AbstractContainerScreen 已算好 leftPos/topPos。
        super.init();
        int left = getGuiLeft();
        int top = getGuiTop();
        int row1 = top + WarehouseLayout.buttonRow1(rowsOnPage);
        int row2 = top + WarehouseLayout.buttonRow2(rowsOnPage);
        int row3 = top + WarehouseLayout.buttonRow3(rowsOnPage);

        hubButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.hub"),
                button -> sendButton(WarehouseMenu.BTN_BACK_TO_HUB)).bounds(left + 8, row3, 160, 20).build());
        upgradeButton = addRenderableWidget(Button.builder(upgradeLabel(),
                button -> sendButton(WarehouseMenu.BTN_UPGRADE)).bounds(left + 8, row1, 160, 20).build());
        sortButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.sort"),
                button -> sendButton(WarehouseMenu.BTN_SORT)).bounds(left + 8, row2, 76, 20).build());
        prevButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.prev"),
                button -> sendButton(WarehouseMenu.BTN_PREV_PAGE)).bounds(left + 88, row2, 32, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.next"),
                button -> sendButton(WarehouseMenu.BTN_NEXT_PAGE)).bounds(left + 124, row2, 44, 20).build());

        upgradeButton.visible = menu.canManage();
        sortButton.visible = menu.canManage();
        prevButton.visible = menu.pages() > 1;
        nextButton.visible = menu.pages() > 1;
        prevButton.active = menu.page() > 0;
        nextButton.active = menu.page() < menu.pages() - 1;
        CursorKeeper.armRestore();
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null && this.menu.containerId >= 0) {
            // 1.21.1 客户端 -> 服务端按钮的正确入口（服务端会回调 menu#clickMenuButton(player, id)）
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    private Component upgradeLabel() {
        int price = menu.price();
        if (price < 0) {
            return Component.translatable("playersuite.hud.maxed");
        }
        return Component.translatable("playersuite.button.upgrade.price", Economy.group(price));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 翻页会重开容器，先恢复光标再做后续刷新（必须在 super.render 之前）
        CursorKeeper.restoreIfPending(this.minecraft);
        CursorKeeper.remember(this.minecraft, mouseX, mouseY);
        // 让按钮上的价格/余额随容器数据实时刷新
        if (upgradeButton != null) {
            int price = menu.price();
            upgradeButton.setMessage(upgradeLabel());
            // 满级（price < 0）时直接置灰，避免无效点击
            upgradeButton.active = price >= 0 && menu.balance() >= price;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = getGuiLeft();
        int top = getGuiTop();
        int width = this.imageWidth;
        int height = this.imageHeight;

        // 面板
        graphics.fill(left, top, left + width, top + height, COLOR_OUTER);
        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, COLOR_PANEL);
        graphics.fill(left + 1, top + 1, left + width - 1, top + WarehouseLayout.GRID_TOP - 1, COLOR_HEADER);

        // 仓库网格底
        int gridLeft = left + WarehouseLayout.GRID_LEFT;
        int gridTop = top + WarehouseLayout.GRID_TOP;
        int gridWidth = 9 * SLOT_PITCH;
        int gridHeight = rowsOnPage * SLOT_PITCH;
        graphics.fill(gridLeft, gridTop, gridLeft + gridWidth, gridTop + gridHeight, COLOR_SLOT_BG);

        // 玩家物品栏底
        int playerTop = top + WarehouseLayout.playerTop(rowsOnPage);
        graphics.fill(gridLeft, playerTop, gridLeft + gridWidth, playerTop + 3 * SLOT_PITCH, COLOR_SLOT_BG);
        int hotbarTop = top + WarehouseLayout.hotbarTop(rowsOnPage);
        graphics.fill(gridLeft, hotbarTop, gridLeft + gridWidth, hotbarTop + SLOT_PITCH, COLOR_SLOT_BG);

        // 网格线
        for (int row = 0; row <= rowsOnPage; row++) {
            int y = gridTop + row * SLOT_PITCH;
            graphics.fill(gridLeft, y, gridLeft + gridWidth, y + 1, COLOR_GRID);
        }
        for (int row = 0; row <= 3; row++) {
            int y = playerTop + row * SLOT_PITCH;
            graphics.fill(gridLeft, y, gridLeft + gridWidth, y + 1, COLOR_GRID);
        }
        for (int col = 0; col <= 9; col++) {
            int x = gridLeft + col * SLOT_PITCH;
            graphics.fill(x, gridTop, x + 1, gridTop + gridHeight, COLOR_GRID);
            graphics.fill(x, playerTop, x + 1, playerTop + 3 * SLOT_PITCH, COLOR_GRID);
            graphics.fill(x, hotbarTop, x + 1, hotbarTop + SLOT_PITCH, COLOR_GRID);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题行：左边标题，右边等级/容量
        graphics.drawString(this.font, this.title, WarehouseLayout.GRID_LEFT, 5, COLOR_TEXT);
        Component levelLine = Component.translatable("playersuite.hud.level",
                menu.level(), menu.capacityValue());
        drawRightAligned(graphics, levelLine, 5, menu.price() < 0 ? COLOR_OK : COLOR_TEXT);

        // 仓库网格与玩家物品栏之间的说明行（多页时把页码接在后面，避免与按钮重叠）
        int infoY = WarehouseLayout.playerTop(rowsOnPage) - 12;
        MutableComponent invLabel = Component.translatable("playersuite.inventory");
        if (menu.pages() > 1) {
            invLabel = invLabel.append(Component.literal("  \u00b7  "))
                    .append(Component.translatable("playersuite.hud.page", menu.page() + 1, menu.pages()));
        }
        graphics.drawString(this.font, invLabel, WarehouseLayout.GRID_LEFT, infoY, COLOR_TEXT_DIM);
        Component balanceLine = Component.translatable("playersuite.hud.balance",
                Economy.group(menu.balance()));
        drawRightAligned(graphics, balanceLine, infoY, menu.price() >= 0 && menu.balance() < menu.price()
                ? COLOR_WARN : COLOR_TEXT);
    }

    private void drawRightAligned(GuiGraphics graphics, Component text, int y, int color) {
        int x = this.imageWidth - WarehouseLayout.GRID_LEFT - this.font.width(text);
        graphics.drawString(this.font, text, Math.max(WarehouseLayout.GRID_LEFT, x), y, color);
    }
}
