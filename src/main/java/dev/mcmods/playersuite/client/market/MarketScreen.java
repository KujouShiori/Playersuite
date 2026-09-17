package dev.mcmods.playersuite.client.market;

import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.market.MarketCatalog;
import dev.mcmods.playersuite.market.MarketMenu;
import dev.mcmods.playersuite.market.MarketService;
import dev.mcmods.playersuite.menu.Layout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 官方市场界面：分类标签行（两行 × 每行 5 个）+ 每页 4 行商品。
 *
 * <p>每行 = 只读图标（份数就是图标上的数字）+ 名称 + 库存/今日剩余 +
 * 第二行买价/卖价 + 第三行「买1/买16/买64/卖1/卖64/自买/自卖」按钮；
 * OP 管理模式下第三行换成「改买价/改卖价/改库存/补货/禁用」。
 *
 * <p>按钮编号全部由 {@link MarketMenu} 的常量决定（编码方案见其类注释），
 * 界面不做任何权限/数值判断，只负责发按钮号和开输入框。
 */
public class MarketScreen extends PageScreen<MarketMenu> {
    /** 自定义数量输入框默认值。 */
    private static final String DEFAULT_QTY = "1";

    private final Button[] rowTradeButtons = new Button[MarketMenu.MAX_ROWS * MarketMenu.TRADE_SLOTS];
    private final Button[] rowManageButtons = new Button[MarketMenu.MAX_ROWS * MarketMenu.MANAGE_SLOTS];
    private Button adminModeButton;
    private Button restockAllButton;
    private Button reloadButton;
    private Button chipPrevButton;
    private Button chipNextButton;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        // 自定义几何：不用基类的 Layout 行高，构造后再覆盖 imageWidth/imageHeight
        super(menu, inventory, title, MarketMenu.MAX_ROWS, false);
        this.imageWidth = MarketMenu.PANEL_WIDTH;
        this.imageHeight = MarketMenu.panelHeight(MarketMenu.MAX_ROWS);
        this.titleLabelX = Layout.GRID_LEFT;
        this.titleLabelY = 5;
    }

    @Override
    protected String textInputFeature() {
        return FeatureKeys.MARKET;
    }

    private int rows() {
        return Math.max(1, menu.rows());
    }

    // ---------------------------------------------------------------- 按钮

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        int rows = rows();
        // 公共按钮改放到本功能自己的底部两行
        backButton.setX(left + Layout.GRID_LEFT);
        prevButton.setX(left + 84);
        nextButton.setX(left + 128);
        int footer2 = top + MarketMenu.footerRow2(rows);
        backButton.setY(footer2);
        prevButton.setY(footer2);
        nextButton.setY(footer2);

        // 分类标签
        int windowStart = menu.chipWindow() * MarketMenu.CHIP_WINDOW;
        for (int i = 0; i < MarketMenu.CHIP_WINDOW; i++) {
            int absolute = windowStart + i;
            if (absolute >= menu.chips().size()) {
                break;
            }
            final int slot = i;
            String name = menu.chips().get(absolute);
            Component label = chipLabel(name);
            Button chip = addRenderableWidget(Button.builder(label,
                            b -> sendButton(MarketMenu.BTN_CATEGORY_BASE + slot))
                    .bounds(left + MarketMenu.chipX(i), top + MarketMenu.chipY(i),
                            MarketMenu.chipWidth(), MarketMenu.CHIP_H)
                    .build());
            chip.active = absolute != menu.categoryIndex();
        }
        if (menu.chips().size() > MarketMenu.CHIP_WINDOW) {
            chipPrevButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.market.btn.chipPrev"),
                            b -> sendButton(MarketMenu.BTN_CHIP_PREV))
                    .bounds(left + MarketMenu.PANEL_WIDTH - 60, top + MarketMenu.CHIP_TOP, 28, MarketMenu.CHIP_H).build());
            chipNextButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.market.btn.chipNext"),
                            b -> sendButton(MarketMenu.BTN_CHIP_NEXT))
                    .bounds(left + MarketMenu.PANEL_WIDTH - 30, top + MarketMenu.CHIP_TOP, 28, MarketMenu.CHIP_H).build());
            chipPrevButton.active = menu.chipWindow() > 0;
            chipNextButton.active = windowStart + MarketMenu.CHIP_WINDOW < menu.chips().size();
        }
        // 「管理模式」等按钮占一行（放在商品区下方的 footer 第一行）
        int footer1 = top + MarketMenu.footerRow1(rows);
        boolean manage = menu.canManage();
        adminModeButton = addRenderableWidget(Button.builder(
                        Component.translatable(menu.hasFlag(MarketMenu.FLAG_ADMIN_MODE)
                                ? "playersuite.market.btn.exitAdmin" : "playersuite.market.btn.admin"),
                        b -> sendButton(MarketMenu.BTN_ADMIN_MODE))
                .bounds(left + Layout.GRID_LEFT, footer1, 60, 20).build());
        adminModeButton.visible = manage;
        adminModeButton.active = menu.hasFlag(MarketMenu.FLAG_EDIT);
        restockAllButton = addRenderableWidget(Button.builder(
                        Component.translatable("playersuite.market.btn.restockAll"),
                        b -> sendButton(MarketMenu.BTN_RESTOCK_ALL))
                .bounds(left + 72, footer1, 60, 20)
                .tooltip(Tooltip.create(Component.translatable("playersuite.market.btn.restockAll.hint")))
                .build());
        reloadButton = addRenderableWidget(Button.builder(
                        Component.translatable("playersuite.market.btn.reload"),
                        b -> sendButton(MarketMenu.BTN_RELOAD))
                .bounds(left + 136, footer1, 60, 20)
                .tooltip(Tooltip.create(Component.translatable("playersuite.market.btn.reload.hint")))
                .build());
        boolean canEdit = menu.hasFlag(MarketMenu.FLAG_EDIT);
        restockAllButton.visible = canEdit;
        reloadButton.visible = canEdit;

        // 行内按钮
        boolean adminUi = menu.hasFlag(MarketMenu.FLAG_ADMIN_MODE) && canEdit;
        for (int r = 0; r < menu.rows() && r < MarketMenu.MAX_ROWS; r++) {
            int buttonY = top + MarketMenu.rowButtonY(r);
            if (adminUi) {
                for (int a = 0; a < MarketMenu.MANAGE_SLOTS; a++) {
                    final int row = r;
                    final int action = a;
                    int x = left + MarketMenu.manageButtonsX() + offsetX(MarketMenu.MANAGE_W, a);
                    Component label = Component.translatable(switch (action) {
                        case 0 -> "playersuite.market.btn.priceBuy";
                        case 1 -> "playersuite.market.btn.priceSell";
                        case 2 -> "playersuite.market.btn.stock";
                        case 3 -> "playersuite.market.btn.restockOne";
                        default -> menu.rowDisabled(row)
                                ? "playersuite.market.btn.enable" : "playersuite.market.btn.disable";
                    });
                    Button button = addRenderableWidget(Button.builder(label, b -> onManageButton(row, action))
                            .bounds(x, buttonY, MarketMenu.MANAGE_W[a], MarketMenu.BUTTON_H)
                            .tooltip(Tooltip.create(Component.translatable("playersuite.market.btn.adminRow.hint")))
                            .build());
                    button.visible = true;
                    rowManageButtons[r * MarketMenu.MANAGE_SLOTS + a] = button;
                }
            } else {
                for (int t = 0; t < MarketMenu.TRADE_SLOTS; t++) {
                    final int row = r;
                    final int tier = t;
                    int x = left + MarketMenu.tradeButtonsX() + offsetX(MarketMenu.TRADE_W, t);
                    boolean sell = MarketMenu.isSellSlot(tier);
                    Component label = Component.translatable(tradeKey(tier));
                    Button button = addRenderableWidget(Button.builder(label, b -> onTradeButton(row, tier))
                            .bounds(x, buttonY, MarketMenu.TRADE_W[t], MarketMenu.BUTTON_H)
                            .build());
                    boolean open = sell ? menu.hasFlag(MarketMenu.FLAG_SELL) : menu.hasFlag(MarketMenu.FLAG_BUY);
                    button.visible = open;
                    rowTradeButtons[r * MarketMenu.TRADE_SLOTS + t] = button;
                }
            }
        }
    }

    private void onTradeButton(int row, int tier) {
        if (tier == MarketMenu.TIER_CUSTOM_BUY) {
            openTextInput(MarketService.ACTION_BUY_QTY + row,
                    Component.translatable("playersuite.market.prompt.buyQty", MarketService.MAX_UNITS),
                    DEFAULT_QTY, true);
            return;
        }
        if (tier == MarketMenu.TIER_CUSTOM_SELL) {
            openTextInput(MarketService.ACTION_SELL_QTY + row,
                    Component.translatable("playersuite.market.prompt.sellQty", MarketService.MAX_UNITS),
                    DEFAULT_QTY, true);
            return;
        }
        sendButton(MarketMenu.BTN_TRADE_BASE + row * MarketMenu.TRADE_SLOTS + tier);
    }

    private void onManageButton(int row, int action) {
        switch (action) {
            case 0 -> openTextInput(MarketService.ACTION_PRICE_BUY + row,
                    Component.translatable("playersuite.market.prompt.buyPrice", menu.rowBuy(row)),
                    String.valueOf(menu.rowBuy(row)), false);
            case 1 -> openTextInput(MarketService.ACTION_PRICE_SELL + row,
                    Component.translatable("playersuite.market.prompt.sellPrice", menu.rowSell(row)),
                    String.valueOf(menu.rowSell(row)), false);
            case 2 -> openTextInput(MarketService.ACTION_STOCK + row,
                    Component.translatable("playersuite.market.prompt.stock", rowStockText(row)),
                    menu.rowStock(row) < 0 ? "" : String.valueOf(menu.rowStock(row)), true);
            default -> sendButton(MarketMenu.BTN_MANAGE_BASE + row * MarketMenu.MANAGE_SLOTS + action);
        }
    }

    /** 交易档位按钮文字键。 */
    private static String tradeKey(int tier) {
        return switch (tier) {
            case 0 -> "playersuite.market.btn.buy1";
            case 1 -> "playersuite.market.btn.buy16";
            case 2 -> "playersuite.market.btn.buy64";
            case 3 -> "playersuite.market.btn.sell1";
            case 4 -> "playersuite.market.btn.sell64";
            case 5 -> "playersuite.market.btn.buyN";
            default -> "playersuite.market.btn.sellN";
        };
    }

    private static int offsetX(int[] widths, int index) {
        int sum = 0;
        for (int i = 0; i < index && i < widths.length; i++) {
            sum += widths[i] + MarketMenu.BUTTON_GAP;
        }
        return sum;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = getGuiLeft();
        int top = getGuiTop();
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        for (int r = 0; r < menu.rows() && r < MarketMenu.MAX_ROWS; r++) {
            int rowTop = MarketMenu.rowTop(r);
            graphics.fill(4, rowTop, MarketMenu.PANEL_WIDTH - 4, rowTop + MarketMenu.ROW_H - 1, Theme.COLOR_SLOT);
            Theme.slotRow(graphics, MarketMenu.slotY(r), 1, 1);
            if (r + 1 < menu.rows()) {
                Theme.line(graphics, 4, rowTop + MarketMenu.ROW_H - 1, MarketMenu.PANEL_WIDTH - 8);
            }
            if (menu.rowDisabled(r)) {
                Theme.highlight(graphics, 5, rowTop + 1, MarketMenu.PANEL_WIDTH - 10, MarketMenu.ROW_H - 3);
            }
        }
        if (menu.rows() == 0) {
            graphics.fill(4, MarketMenu.CONTENT_TOP, MarketMenu.PANEL_WIDTH - 4,
                    MarketMenu.CONTENT_TOP + MarketMenu.ROW_H - 1, Theme.COLOR_SLOT);
        }
        Theme.line(graphics, Layout.GRID_LEFT, MarketMenu.footerRow1(rows()) - 3,
                MarketMenu.PANEL_WIDTH - 2 * Layout.GRID_LEFT);
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, Layout.GRID_LEFT, 5, Theme.COLOR_TEXT);
        Theme.rightText(graphics, Component.translatable("playersuite.hud.balance",
                Economy.label(menu.balance())), this.imageWidth, 5, Theme.COLOR_TEXT);

        for (int r = 0; r < menu.rows() && r < MarketMenu.MAX_ROWS; r++) {
            drawRow(graphics, r);
        }
        if (menu.rows() == 0) {
            Theme.text(graphics, Component.translatable(menu.hasFlag(MarketMenu.FLAG_EMPTY)
                            ? "playersuite.market.list.emptyCategory" : "playersuite.market.list.empty"),
                    Layout.GRID_LEFT, MarketMenu.CONTENT_TOP + 6, Theme.COLOR_TEXT_DIM);
        }

        // 第二行（与返回/翻页按钮同行，只占右半边，避免压住按钮）：页码
        if (menu.pages() > 1) {
            Theme.rightText(graphics, Component.translatable("playersuite.hud.page",
                    menu.page() + 1, menu.pages()), this.imageWidth,
                    MarketMenu.footerRow2(rows()), Theme.COLOR_TEXT_DIM);
        }
        // 第三行：今日剩余限购（左） + OP 统计（右）
        int footer3 = MarketMenu.footerRow3(rows());
        if (menu.hasFlag(MarketMenu.FLAG_DAILY)) {
            Theme.text(graphics, Component.translatable("playersuite.market.hud.daily",
                    Math.max(0, menu.dailyRemaining())), Layout.GRID_LEFT, footer3, Theme.COLOR_TEXT_DIM);
        } else {
            Theme.text(graphics, Component.translatable("playersuite.market.hud.dailyNone"),
                    Layout.GRID_LEFT, footer3, Theme.COLOR_TEXT_DIM);
        }
        if (menu.canManage()) {
            // 统计数值全部由服务端下发（managePermission 才画）
            Theme.rightText(graphics, Component.translatable("playersuite.market.hud.stats",
                            menu.statsEntries(), menu.statsDisabled(), menu.statsStock()),
                    this.imageWidth, footer3, Theme.COLOR_TEXT_DIM);
        }
    }

    private void drawRow(GuiGraphics graphics, int r) {
        ItemStack display = menu.rowDisplay(r);
        int nameY = MarketMenu.nameY(r);
        int infoY = MarketMenu.infoY(r);
        Component name = display.isEmpty()
                ? Component.literal(menu.rowId(r)) : display.getHoverName();
        Component rowName = menu.rowDisabled(r)
                ? Component.translatable("playersuite.market.row.disabledPrefix", name) : name;
        Theme.text(graphics, clip(rowName.getString(), MarketMenu.NAME_W),
                Layout.GRID_LEFT, nameY, menu.rowDisabled(r) ? Theme.COLOR_WARN : Theme.COLOR_TEXT);
        Theme.text(graphics, "x" + menu.rowUnit(r), MarketMenu.COL_UNIT, nameY, Theme.COLOR_TEXT_DIM);
        Theme.text(graphics, Component.translatable("playersuite.market.row.stock", rowStockText(r)),
                MarketMenu.COL_STOCK, nameY,
                menu.rowStock(r) < 0 ? Theme.COLOR_TEXT_DIM : Theme.COLOR_OK);
        if (menu.hasFlag(MarketMenu.FLAG_DAILY)) {
            int left = menu.rowDailyLeft(r);
            Theme.text(graphics, Component.translatable("playersuite.market.row.daily",
                    String.valueOf(Math.max(0, left))),
                    MarketMenu.COL_DAILY, nameY,
                    left <= 0 ? Theme.COLOR_WARN : Theme.COLOR_TEXT_DIM);
        }
        // 第二行：买价 / 卖价 / 已改价标记
        Theme.text(graphics, Component.translatable("playersuite.market.row.buy",
                        Economy.formatString(menu.rowBuy(r))),
                MarketMenu.COL_BUY, infoY, menu.rowBuy(r) > 0 ? Theme.COLOR_TEXT : Theme.COLOR_TEXT_DIM);
        Theme.text(graphics, Component.translatable("playersuite.market.row.sell",
                        Economy.formatString(menu.rowSell(r))),
                MarketMenu.COL_SELL, infoY, menu.rowSell(r) > 0 ? Theme.COLOR_TEXT : Theme.COLOR_TEXT_DIM);
        if (menu.rowCustomized(r)) {
            Theme.text(graphics, Component.translatable("playersuite.market.row.customized"),
                    MarketMenu.COL_MARK, infoY, Theme.COLOR_HIGHLIGHT);
        }
    }

    /** 库存文字：不限 / 具体份数。 */
    private String rowStockText(int row) {
        int stock = menu.rowStock(row);
        if (stock < 0) {
            return "∞";
        }
        return String.valueOf(stock);
    }

    /** 分类标签文字：「全部」「未分类」走语言键，其余配置里的分类名原样显示。 */
    private static Component chipLabel(String name) {
        if (MarketCatalog.CATEGORY_ALL.equals(name)) {
            return Component.translatable("playersuite.market.category.all");
        }
        if (MarketCatalog.UNCATEGORIZED.equals(name)) {
            return Component.translatable("playersuite.market.category.uncategorized");
        }
        return Component.literal(name);
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 买不起就把买入按钮置灰（只是提示，服务端还会真判一次）
        long balance = menu.balance();
        for (int r = 0; r < MarketMenu.MAX_ROWS; r++) {
            for (int t = 0; t < MarketMenu.TRADE_SLOTS; t++) {
                Button button = rowTradeButtons[r * MarketMenu.TRADE_SLOTS + t];
                if (button == null) {
                    continue;
                }
                int tier = MarketMenu.TRADE_TIERS[t];
                long price = MarketMenu.isSellSlot(t) ? 0L : menu.rowBuy(r);
                boolean canPay = MarketMenu.isSellSlot(t) || tier <= 0
                        || price <= 0L || balance >= price * tier;
                button.active = canPay && !menu.rowDisabled(r);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (adminModeButton != null && adminModeButton.visible && adminModeButton.isHovered()) {
            graphics.renderComponentTooltip(this.font, List.<Component>of(
                            Component.translatable("playersuite.market.btn.admin.hint"),
                            Component.translatable("playersuite.market.hud.stats",
                                    menu.statsEntries(), menu.statsDisabled(), menu.statsStock()),
                            Component.translatable("playersuite.market.hud.tracked", menu.statsTracked())),
                    mouseX, mouseY);
        }
    }

    @Override
    protected Component headerRight() {
        return null;
    }

    @Override
    protected Component statusLine() {
        return null;
    }
}
