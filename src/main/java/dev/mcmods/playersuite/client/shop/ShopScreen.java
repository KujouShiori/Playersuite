package dev.mcmods.playersuite.client.shop;

import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.shop.ShopMenu;
import dev.mcmods.playersuite.shop.ShopService;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 我的商店界面（OP 查看他人时也用它，按钮组自动切换）。
 *
 * <p>面板加宽到 {@link ShopMenu#PANEL_WIDTH}，每行 24px：左侧托管槽，
 * 中间商品名与「xN · 单价」，右侧按钮组。完全矩形绘制，不引用任何材质。
 */
public class ShopScreen extends PageScreen<ShopMenu> {
    private static final int NAME_X = ShopMenu.TEXT_X;
    /** 行占用位掩码（按实时槽位内容算），变化时重建行按钮。 */
    private int rowMask = -1;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.rows(), true);
        this.imageWidth = ShopMenu.PANEL_WIDTH;
        this.imageHeight = ShopMenu.panelHeight(menu.rows());
    }

    /**
     * 行按钮的显示/隐藏由实时托管槽内容决定（上架/取空后无需重开菜单），
     * 掩码变化才 rebuildWidgets，避免每 tick 重建。
     */
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
        int rows = menu.rows();
        for (int i = 0; i < rows && i < ShopMenu.MAX_ROWS; i++) {
            if (menu.getSlot(i).hasItem()) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        rowMask = computeRowMask();
        int rows = menu.rows();
        // 公共翻页按钮挪到本页真实底部
        backButton.setY(top + ShopMenu.footerRow2(rows));
        prevButton.setY(top + ShopMenu.footerRow2(rows));
        nextButton.setY(top + ShopMenu.footerRow2(rows));

        // 底部第一行：店名 / 逛店 /（管理员）清空
        addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.name"),
                        b -> openTextInput(ShopService.ACTION_NAME,
                                Component.translatable("playersuite.shop.prompt.name"),
                                menu.shopName(), false))
                .bounds(left + 8, top + ShopMenu.footerRow1(rows), 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.browse"),
                        b -> sendButton(ShopMenu.BTN_BROWSE))
                .bounds(left + 84, top + ShopMenu.footerRow1(rows), 72, 20).build());
        if (menu.adminView()) {
            addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.clear"),
                            b -> sendButton(ShopMenu.BTN_CLEAR))
                    .bounds(left + 160, top + ShopMenu.footerRow1(rows), 72, 20).build());
        }

        // 行按钮（按实时槽位判断是否有商品；服务端永远二次校验）
        for (int i = 0; i < rows; i++) {
            if ((rowMask & (1 << i)) == 0) {
                continue;   // 空货架没有可操作按钮（直接往槽里放物品即上架）
            }
            final int row = i;
            int y = top + ShopMenu.rowTop(i) + 2;
            if (menu.selfView()) {
                int x = left + ShopMenu.PANEL_WIDTH - 8 - (30 + 2 + 22 + 2 + 28 + 2 + 30);
                addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.price"),
                                b -> openTextInput(ShopService.ACTION_PRICE_PREFIX + menu.rowSlot(row),
                                        Component.translatable("playersuite.shop.prompt.price"),
                                        String.valueOf(menu.rowPrice(row)), true))
                        .bounds(x, y, 30, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.stock1"),
                                b -> sendButton(ShopMenu.BTN_ADD1_BASE + row))
                        .bounds(x + 32, y, 22, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.stock64"),
                                b -> sendButton(ShopMenu.BTN_ADD64_BASE + row))
                        .bounds(x + 56, y, 28, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.unlist"),
                                b -> sendButton(ShopMenu.BTN_UNLIST_BASE + row))
                        .bounds(x + 86, y, 30, 20).build());
            } else if (menu.manageView()) {
                int x = left + ShopMenu.PANEL_WIDTH - 8 - (52 + 2 + (menu.adminView() ? 52 : 0));
                addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.force"),
                                b -> sendButton(ShopMenu.BTN_FORCE_BASE + row))
                        .bounds(x, y, 52, 20).build());
                if (menu.adminView()) {
                    addRenderableWidget(Button.builder(Component.translatable("playersuite.shop.btn.return"),
                                    b -> sendButton(ShopMenu.BTN_RETURN_BASE + row))
                            .bounds(x + 54, y, 52, 20).build());
                }
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = getGuiLeft();
        int top = getGuiTop();
        int rows = menu.rows();
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        for (int i = 0; i < rows; i++) {
            Theme.slotRow(graphics, ShopMenu.rowTop(i) + 3, 1, 1);
            Theme.line(graphics, 8, ShopMenu.rowTop(i) + ShopMenu.ROW_H, ShopMenu.PANEL_WIDTH - 16);
        }
        int invTop = ShopMenu.inventoryTop(rows);
        Theme.slotRow(graphics, invTop, 4, 9);
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int rows = menu.rows();
        graphics.drawString(this.font, this.title, Layout.GRID_LEFT, 5, Theme.COLOR_TEXT);
        if (menu.selfView()) {
            Theme.rightText(graphics, Component.translatable("playersuite.hud.balance",
                    Economy.label(menu.balance())), this.imageWidth, 5, Theme.COLOR_TEXT);
        } else {
            Theme.rightText(graphics, menu.ownerName(), this.imageWidth, 5, Theme.COLOR_TEXT);
        }

        int nameW = buttonFreeWidth();
        for (int i = 0; i < rows; i++) {
            ItemStack display = menu.rowDisplay(i);
            int top = ShopMenu.rowTop(i);
            if (menu.rowStock(i) <= 0 && display.isEmpty()) {
                Theme.text(graphics, Component.translatable("playersuite.shop.row.empty",
                        menu.rowSlot(i) + 1), NAME_X, top + 6, Theme.COLOR_TEXT_DIM);
                continue;
            }
            Theme.text(graphics, clip(display.getHoverName().getString(), nameW),
                    NAME_X, top + 2, Theme.COLOR_TEXT);
            int shownStock = Math.max(menu.rowStock(i), display.getCount());
            Theme.text(graphics, "x" + Economy.group(shownStock),
                    NAME_X, top + 12, Theme.COLOR_OK);
            if (menu.rowPrice(i) > 0) {
                Theme.text(graphics, Component.translatable("playersuite.shop.row.price",
                        Economy.formatString(menu.rowPrice(i))), NAME_X + 44, top + 12, Theme.COLOR_WARN);
            } else {
                Theme.text(graphics, Component.translatable("playersuite.shop.row.priceNone"),
                        NAME_X + 44, top + 12, Theme.COLOR_WARN);
            }
        }

        Theme.text(graphics, Component.translatable("playersuite.inventory"),
                Layout.GRID_LEFT, ShopMenu.inventoryTop(rows) - 10, Theme.COLOR_TEXT_DIM);
        if (menu.pages() > 1) {
            Theme.text(graphics, Component.translatable("playersuite.hud.page",
                    menu.page() + 1, menu.pages()), Layout.GRID_LEFT,
                    ShopMenu.footerRow2(rows) + 22, Theme.COLOR_TEXT_DIM);
        }
        // 底部统计行（在翻页按钮下方，无遮挡）
        Theme.rightText(graphics, Component.translatable("playersuite.shop.hud.stats",
                        menu.listingCount(), menu.listedUnits(),
                        Economy.label(menu.todayIncome())),
                this.imageWidth, ShopMenu.footerRow2(rows) + 22, Theme.COLOR_TEXT_DIM);
    }

    /** 第一行商品名的最大像素宽（避开右侧按钮列）。 */
    private int buttonFreeWidth() {
        if (menu.selfView()) {
            return 92;
        }
        return menu.manageView() ? 100 : this.imageWidth - ShopMenu.TEXT_X - 10;
    }

    /** 按像素截断（中文安全：逐码点回退）。 */
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
