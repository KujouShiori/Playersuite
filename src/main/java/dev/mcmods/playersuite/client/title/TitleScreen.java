package dev.mcmods.playersuite.client.title;

import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.title.TitleMenu;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 称号界面：称号墙（每行 9 个展示槽图标，已拥有画小绿点、佩戴中用 Theme.highlight 描边）
 * 与详情页共用一个 Screen，按 {@link TitleMenu#mode()} 分支绘制与摆放按钮。
 *
 * <p>全部使用 {@link Theme} 矩形绘制，不引用任何材质；显示名来自配置，
 * 一律 {@link Component#literal}（不伪装成 translatable）。
 */
public class TitleScreen extends PageScreen<TitleMenu> {

    private final Button[] cellButtons = new Button[TitleMenu.PER_PAGE];
    private Button viewOtherButton;
    private Button grantInputButton;
    private Button revokeInputButton;
    private Button buyButton;
    private Button equipButton;
    private Button unequipButton;
    private Button grantButton;
    private Button revokeButton;
    private Button forceButton;
    private Button backButton;

    public TitleScreen(TitleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.contentRows(), false);
    }

    @Override
    protected String textInputFeature() {
        return FeatureOpeners.TITLE;
    }

    // ---------------------------------------------------------------- 按钮

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        if (menu.mode() == TitleMenu.MODE_GRID) {
            for (int i = 0; i < TitleMenu.PER_PAGE; i++) {
                final int cell = i;
                cellButtons[i] = addRenderableWidget(Button.builder(
                        Component.translatable("playersuite.title.row.view"),
                        b -> sendButton(TitleMenu.BTN_INFO_BASE + cell))
                        .bounds(left + TitleMenu.cellBtnX(cell), top + TitleMenu.cellBtnY(cell),
                                TitleMenu.CELL_BTN_W, TitleMenu.CELL_BTN_H).build());
                cellButtons[i].visible = cell < menu.cells();
            }
            viewOtherButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.title.button.viewOther"),
                    b -> openTextInput("view_target",
                            Component.translatable("playersuite.title.prompt.viewTarget"), "", false))
                    .bounds(left + 8, row1, 50, 20).build());
            grantInputButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.title.button.grantId"),
                    b -> openTextInput("grant",
                            Component.translatable("playersuite.title.prompt.grant"), "", false))
                    .bounds(left + 63, row1, 50, 20).build());
            revokeInputButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.title.button.revokeId"),
                    b -> openTextInput("revoke",
                            Component.translatable("playersuite.title.prompt.revoke"), "", false))
                    .bounds(left + 118, row1, 50, 20).build());
            viewOtherButton.visible = menu.hasFlag(TitleMenu.FLAG_MANAGE);
            grantInputButton.visible = menu.hasFlag(TitleMenu.FLAG_MANAGE);
            revokeInputButton.visible = menu.hasFlag(TitleMenu.FLAG_MANAGE);
        } else {
            boolean self = !menu.hasFlag(TitleMenu.FLAG_OTHER);
            boolean adminView = menu.hasFlag(TitleMenu.FLAG_ADMIN) && !self;
            boolean owned = menu.detailOwned();
            boolean equipped = menu.detailEquipped();
            buyButton = addButton("playersuite.title.button.buy", TitleMenu.BTN_BUY, left + 8, row1);
            equipButton = addButton("playersuite.title.button.equip", TitleMenu.BTN_EQUIP, left + 62, row1);
            unequipButton = addButton("playersuite.title.button.unequip", TitleMenu.BTN_UNEQUIP, left + 116, row1);
            grantButton = addButton("playersuite.title.button.grant", TitleMenu.BTN_GRANT, left + 8, row1);
            revokeButton = addButton("playersuite.title.button.revoke", TitleMenu.BTN_REVOKE, left + 62, row1);
            forceButton = addButton("playersuite.title.button.force", TitleMenu.BTN_FORCE_EQUIP, left + 116, row1);
            backButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.title.button.back"),
                    b -> sendButton(TitleMenu.BTN_BACK_GRID))
                    .bounds(left + 72, row2, 60, 20).build());
            buyButton.visible = self && !owned;
            equipButton.visible = self && owned && !equipped;
            unequipButton.visible = self && equipped;
            grantButton.visible = adminView && !owned;
            revokeButton.visible = adminView && owned;
            forceButton.visible = adminView && owned && !equipped;
        }
    }

    private Button addButton(String key, int buttonId, int x, int y) {
        return addRenderableWidget(Button.builder(Component.translatable(key),
                b -> sendButton(buttonId)).bounds(x, y, 52, 20).build());
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void renderPageBackground(GuiGraphics graphics) {
        if (menu.mode() != TitleMenu.MODE_GRID) {
            return;
        }
        for (int i = 0; i < menu.clientCells().size() && i < TitleMenu.PER_PAGE; i++) {
            TitleMenu.CellView cell = menu.clientCells().get(i);
            int x = TitleMenu.cellX(i);
            int y = TitleMenu.cellY(i);
            if ((cell.bits() & TitleMenu.BIT_OWNED) != 0) {
                // 已拥有：右上角小绿点
                graphics.fill(x + 14, y + 1, x + 17, y + 4, Theme.COLOR_OK);
            }
            if ((cell.bits() & TitleMenu.BIT_EQUIPPED) != 0) {
                // 当前佩戴：Theme.highlight 描边
                Theme.highlight(graphics, x, y, Layout.PITCH, Layout.PITCH);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        if (menu.mode() == TitleMenu.MODE_DETAIL) {
            Component name = coloredName(menu.detailName(), menu.detailColor());
            int nameW = Math.max(20, this.font.width(name));
            Theme.text(graphics, name, (this.imageWidth - nameW) / 2, TitleMenu.DETAIL_NAME_Y, Theme.COLOR_TEXT);
            Theme.line(graphics, Layout.GRID_LEFT, TitleMenu.DETAIL_NAME_Y + 12, 160);
            Theme.text(graphics, Component.translatable("playersuite.title.detail.price",
                    Economy.label(menu.detailPrice())), Layout.GRID_LEFT, TitleMenu.DETAIL_PRICE_Y, Theme.COLOR_TEXT);
            Theme.text(graphics, Component.translatable(
                            menu.detailOwned() ? "playersuite.title.detail.ownedYes" : "playersuite.title.detail.ownedNo"),
                    Layout.GRID_LEFT, TitleMenu.DETAIL_OWNED_Y,
                    menu.detailOwned() ? Theme.COLOR_OK : Theme.COLOR_TEXT_DIM);
            Theme.text(graphics, Component.translatable(
                            menu.detailEquipped() ? "playersuite.title.detail.equippedYes" : "playersuite.title.detail.equippedNo"),
                    Layout.GRID_LEFT, TitleMenu.DETAIL_EQUIP_Y,
                    menu.detailEquipped() ? Theme.COLOR_OK : Theme.COLOR_TEXT_DIM);
        }
    }

    /** 配置显示名：颜色名解析失败按无色处理（不崩）。 */
    static Component coloredName(String name, String colorName) {
        net.minecraft.network.chat.MutableComponent c = Component.literal(name == null ? "" : name);
        if (colorName == null || colorName.isEmpty()) {
            return c;
        }
        ChatFormatting color = ChatFormatting.getByName(colorName.toLowerCase(java.util.Locale.ROOT));
        return color == null ? c : c.withStyle(color);
    }

    @Override
    protected Component statusLine() {
        if (menu.mode() == TitleMenu.MODE_GRID) {
            if (menu.total() <= 0) {
                return Component.translatable("playersuite.title.grid.empty");
            }
            if (menu.hasFlag(TitleMenu.FLAG_OTHER)) {
                return Component.translatable("playersuite.title.grid.statusOther",
                        menu.ownedCount(), menu.maxOwned());
            }
            return Component.translatable("playersuite.title.grid.status", menu.ownedCount(), menu.maxOwned());
        }
        return Component.translatable("playersuite.title.detail.tip");
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (viewOtherButton != null && viewOtherButton.visible && viewOtherButton.isHovered()) {
            showHint(graphics, mouseX, mouseY, "playersuite.title.button.viewOther.hint");
            return;
        }
        if (grantInputButton != null && grantInputButton.visible && grantInputButton.isHovered()) {
            showHint(graphics, mouseX, mouseY, "playersuite.title.button.grantId.hint");
            return;
        }
        if (revokeInputButton != null && revokeInputButton.visible && revokeInputButton.isHovered()) {
            showHint(graphics, mouseX, mouseY, "playersuite.title.button.revokeId.hint");
        }
    }

    private void showHint(GuiGraphics graphics, int mouseX, int mouseY, String key) {
        graphics.renderComponentTooltip(this.font,
                List.<Component>of(Component.translatable(key)), mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (buyButton != null && buyButton.visible) {
            // 免费称号（价格 0）不需要余额；付费称号余额不足时禁用（服务端仍会再判一次）
            buyButton.active = menu.detailPrice() <= 0L || menu.balance() >= menu.detailPrice();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
