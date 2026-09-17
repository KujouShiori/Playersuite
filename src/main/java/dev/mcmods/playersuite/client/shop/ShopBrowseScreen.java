package dev.mcmods.playersuite.client.shop;

import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.shop.ShopBrowseMenu;
import dev.mcmods.playersuite.shop.ShopMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 逛店界面：每行 = 只读商品图标 + 名称/库存/单价 + 卖家与店名 + 三个数量档按钮。
 *
 * <p>注意：行按钮编号 {@code BTN_BUY_BASE + 行号 * 3 + 档位}，
 * 档位固定三档（1/16/64），全部判定由服务端完成。
 */
public class ShopBrowseScreen extends PageScreen<ShopBrowseMenu> {
    private static final int ROW_H = ShopMenu.ROW_H;
    private static final int NAME_X = ShopMenu.TEXT_X;
    private static final int BUY_W1 = 28;
    private static final int BUY_W16 = 34;
    private static final int BUY_W64 = 34;
    private static final int BUY_TOTAL = BUY_W1 + 4 + BUY_W16 + 4 + BUY_W64;
    /** 行商品位掩码（实时展示槽）：被他人买空后隐藏该行按钮。 */
    private int rowMask = -1;

    public ShopBrowseScreen(ShopBrowseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Math.max(1, menu.rows()), false);
        this.imageWidth = ShopMenu.PANEL_WIDTH;
        this.imageHeight = panelHeight(Math.max(1, menu.rows()));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int mask = computeRowMask();
        if (mask != rowMask) {
            rowMask = mask;
            rebuildWidgets();
        }
    }

    private int computeRowMask() {
        int mask = 0;
        for (int i = 0; i < menu.rows() && i < ShopMenu.MAX_ROWS; i++) {
            if (menu.getSlot(i).hasItem()) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private static int footerRow(int rows) {
        return Layout.contentTop() + rows * ROW_H + 10;
    }

    private static int panelHeight(int rows) {
        return footerRow(rows) + 48;
    }

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        rowMask = computeRowMask();
        int rows = Math.max(1, menu.rows());
        backButton.setX(left + 8);
        prevButton.setX(left + 84);
        nextButton.setX(left + 128);
        backButton.setY(top + footerRow(rows) + 24);
        prevButton.setY(top + footerRow(rows) + 24);
        nextButton.setY(top + footerRow(rows) + 24);

        addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.myshop"),
                        b -> sendButton(ShopBrowseMenu.BTN_MY_SHOP))
                .bounds(left + 8, top + footerRow(rows) - 4, 72, 20).build());

        for (int i = 0; i < menu.rows(); i++) {
            if ((1 << i & rowMask) == 0 && rowMask >= 0) {
                continue;   // 已被买空/下架：隐藏按钮（服务端仍会二次校验）
            }
            final int row = i;
            int y = top + ShopMenu.rowTop(row) + 2;
            int x = left + ShopMenu.PANEL_WIDTH - 8 - BUY_TOTAL;
            addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.buy.1"),
                            b -> sendButton(ShopBrowseMenu.BTN_BUY_BASE + row * ShopBrowseMenu.TIER_COUNT))
                    .bounds(x, y, BUY_W1, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.buy.16"),
                            b -> sendButton(ShopBrowseMenu.BTN_BUY_BASE + row * ShopBrowseMenu.TIER_COUNT + 1))
                    .bounds(x + BUY_W1 + 4, y, BUY_W16, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.buy.64"),
                            b -> sendButton(ShopBrowseMenu.BTN_BUY_BASE + row * ShopBrowseMenu.TIER_COUNT + 2))
                    .bounds(x + BUY_W1 + 4 + BUY_W16 + 4, y, BUY_W64, 20).build());
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = getGuiLeft();
        int top = getGuiTop();
        int rows = Math.max(1, menu.rows());
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        for (int i = 0; i < menu.rows(); i++) {
            Theme.slotRow(graphics, ShopMenu.rowTop(i) + 3, 1, 1);
        }
        Theme.line(graphics, 8, footerRow(rows) - 12, ShopMenu.PANEL_WIDTH - 16);
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, Layout.GRID_LEFT, 5, Theme.COLOR_TEXT);
        Theme.rightText(graphics, Component.translatable("playersuite.hud.balance",
                Economy.label(menu.balance())), this.imageWidth, 5, Theme.COLOR_TEXT);

        // 右侧留给三档购买按钮（BUY_TOTAL 宽），文字一律限制在按钮列左侧
        int textRight = ShopMenu.PANEL_WIDTH - 8 - BUY_TOTAL - 6;
        for (int i = 0; i < menu.rows(); i++) {
            ItemStack display = menu.rowDisplay(i);
            int top = ShopMenu.rowTop(i);
            Theme.text(graphics, clip(display.getHoverName().getString(), textRight - NAME_X - 30),
                    NAME_X, top + 2, Theme.COLOR_TEXT);
            Theme.text(graphics, "x" + Economy.group(menu.rowStock(i)), textRight - 26, top + 2,
                    Theme.COLOR_OK);
            Theme.text(graphics, Component.translatable("playersuite.shop.row.price",
                    Economy.formatString(menu.rowPrice(i))), NAME_X, top + 12, Theme.COLOR_WARN);
            String seller = menu.rowShop(i).isEmpty()
                    ? menu.rowSeller(i) : menu.rowShop(i) + " · " + menu.rowSeller(i);
            Theme.text(graphics, clip(seller, textRight - (NAME_X + 64)), NAME_X + 64, top + 12,
                    Theme.COLOR_TEXT_DIM);
        }
        if (menu.rows() == 0) {
            Theme.text(graphics, Component.translatable("playersuite.shop.browse.empty"),
                    Layout.GRID_LEFT, Layout.contentTop() + 6, Theme.COLOR_TEXT_DIM);
        }
        int rows = Math.max(1, menu.rows());
        Theme.text(graphics, Component.translatable("playersuite.shop.browse.total", menu.totalEntries()),
                90, footerRow(rows) + 2, Theme.COLOR_TEXT_DIM);
        if (menu.pages() > 1) {
            Theme.rightText(graphics, Component.translatable("playersuite.hud.page",
                    menu.page() + 1, menu.pages()), this.imageWidth, footerRow(rows) + 2,
                    Theme.COLOR_TEXT_DIM);
        }
    }

    private String clip(String raw, int maxWidth) {
        Font font = Theme.font();
        String s = raw == null ? "" : raw;
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + "…") > maxWidth) {
            end = s.offsetByCodePoints(end, -1);
        }
        return s.substring(0, Math.max(0, end)) + "…";
    }

    @Override
    protected Component statusLine() {
        return null;
    }

    @Override
    protected Component headerRight() {
        return null;
    }

    @Override
    protected String textInputFeature() {
        return FeatureKeys.SHOP;
    }
}
