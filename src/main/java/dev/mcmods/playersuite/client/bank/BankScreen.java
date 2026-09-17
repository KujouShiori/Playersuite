package dev.mcmods.playersuite.client.bank;

import dev.mcmods.playersuite.bank.BankData;
import dev.mcmods.playersuite.bank.BankMenu;
import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 银行界面：一个 Screen 承担「主页 / 流水页」两种模式。
 *
 * <p>按钮只发编号或打开文本框，金额、权限、冷却、上限全部在服务端再判一次；
 * 几何常量全部来自 {@link BankMenu}，绘制只用矩形，不引用任何材质。
 */
public class BankScreen extends PageScreen<BankMenu> {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 文本输入动作名，必须与服务端 {@code BankService.ACTION_*} 完全一致。 */
    private static final String ACTION_DEPOSIT = "deposit";
    private static final String ACTION_WITHDRAW = "withdraw";
    private static final String ACTION_ADMIN_SET = "admin_set";
    private static final String ACTION_ADMIN_OPEN = "admin_open";

    /** 底部第一行按钮的宽度与间距（用 row1，不占基类的 row2）。 */
    private static final int NAV_W = 60;
    private static final int NAV_STEP = 64;
    /** 内容区宽度：与基类画的 9 柱网格同宽（Theme.slotRow 的 columns * PITCH）。 */
    private static final int BANK_GRID_WIDTH = 9 * Layout.PITCH;

    public BankScreen(BankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BankMenu.CONTENT_ROWS, false);
    }

    @Override
    protected String textInputFeature() {
        return FeatureOpeners.BANK;
    }

    // ------------------------------------------------------------ 按钮

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        // left/top 是面板左上角的绝对坐标，内容区从 Layout.GRID_LEFT 开始（与基类公共按钮的 left+8 对齐）
        int x0 = left + Layout.GRID_LEFT;
        addButton(x0, row1, NAV_W,
                Component.translatable(menu.isHistory()
                        ? "playersuite.bank.button.backMain" : "playersuite.bank.button.history"),
                button -> sendButton(menu.isHistory() ? BankMenu.BTN_BACK_MAIN : BankMenu.BTN_HISTORY));
        if (menu.canManage()) {
            addButton(x0 + NAV_STEP, row1, NAV_W,
                    Component.translatable(menu.selfView()
                            ? "playersuite.bank.button.openOther" : "playersuite.bank.button.self"),
                    button -> {
                        if (menu.selfView()) {
                            openTextInput(ACTION_ADMIN_OPEN,
                                    Component.translatable("playersuite.bank.prompt.player"), "", false);
                        } else {
                            sendButton(BankMenu.BTN_BACK_MAIN);
                        }
                    });
        }

        if (menu.isHistory()) {
            return;                             // 流水页只有列表，动作都在底部一行
        }
        if (!menu.selfView()) {
            addAdminButtons(x0, top);
            return;
        }

        long[] quick = menu.quickAmounts();
        int depositY = top + BankMenu.BUTTON_DEPOSIT_Y;
        int withdrawY = top + BankMenu.BUTTON_WITHDRAW_Y;
        for (int i = 0; i < quick.length && i < BankMenu.QUICK_SLOTS; i++) {
            final int slot = i;
            int x = x0 + i * BankMenu.QUICK_STEP;
            addButton(x, depositY, BankMenu.QUICK_W, Component.literal(Long.toString(quick[i])),
                    button -> sendButton(BankMenu.BTN_QUICK_DEPOSIT + slot));
            addButton(x, withdrawY, BankMenu.QUICK_W, Component.literal(Long.toString(quick[i])),
                    button -> sendButton(BankMenu.BTN_QUICK_WITHDRAW + slot));
        }
        int customY = top + BankMenu.BUTTON_CUSTOM_Y;
        addButton(x0, customY, BankMenu.CUSTOM_W,
                Component.translatable("playersuite.bank.button.depositAll"),
                button -> sendButton(BankMenu.BTN_DEPOSIT_ALL));
        addButton(x0 + BankMenu.CUSTOM_STEP, customY, BankMenu.CUSTOM_W,
                Component.translatable("playersuite.bank.button.customDeposit"),
                button -> openTextInput(ACTION_DEPOSIT,
                        Component.translatable("playersuite.bank.prompt.deposit",
                                Economy.formatString(menu.minDeposit())), "", true));
        addButton(x0 + BankMenu.CUSTOM_STEP * 2, customY, BankMenu.CUSTOM_W,
                Component.translatable("playersuite.bank.button.customWithdraw"),
                button -> openTextInput(ACTION_WITHDRAW,
                        Component.translatable("playersuite.bank.prompt.withdraw",
                                Economy.formatString(menu.principal())), "", true));
    }

    /** 管理他人：设定本金 / 清零 / 冻结（按钮只在 admin 权限下出现，服务端仍会二次校验）。 */
    private void addAdminButtons(int x0, int top) {
        if (!menu.admin()) {
            return;
        }
        int y = top + BankMenu.BUTTON_ADMIN_Y;
        addButton(x0, y, BankMenu.CUSTOM_W,
                Component.translatable("playersuite.bank.button.setPrincipal"),
                button -> openTextInput(ACTION_ADMIN_SET,
                        Component.translatable("playersuite.bank.prompt.setPrincipal",
                                Economy.formatString(menu.principal())),
                        Long.toString(menu.principal()), true));
        addButton(x0 + BankMenu.CUSTOM_STEP, y, BankMenu.CUSTOM_W,
                Component.translatable("playersuite.bank.button.clear"),
                button -> sendButton(BankMenu.BTN_ADMIN_CLEAR));
        addButton(x0 + BankMenu.CUSTOM_STEP * 2, y, BankMenu.CUSTOM_W,
                Component.translatable(menu.frozen()
                        ? "playersuite.bank.button.unfreeze" : "playersuite.bank.button.freeze"),
                button -> sendButton(BankMenu.BTN_ADMIN_FREEZE));
    }

    private void addButton(int x, int y, int width, Component label, Button.OnPress onPress) {
        addRenderableWidget(Button.builder(label, onPress).bounds(x, y, width, BankMenu.BUTTON_H).build());
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void renderPageBackground(GuiGraphics graphics) {
        // 银行页没有格子：把基类画的内容区网格抹成面板底色，只留一个干净的文本区。
        int top = Layout.contentTop();
        int bottom = top + BankMenu.CONTENT_ROWS * Layout.PITCH;
        graphics.fill(Layout.GRID_LEFT, top, Layout.GRID_LEFT + BANK_GRID_WIDTH, bottom, Theme.COLOR_PANEL);
        Theme.line(graphics, Layout.GRID_LEFT, top, BANK_GRID_WIDTH);
        Theme.line(graphics, Layout.GRID_LEFT, bottom - 1, BANK_GRID_WIDTH);
        if (menu.isHistory()) {
            // 流水页每条一个分隔线，不靠格子也能一眼扫到行界
            for (int i = 1; i < Math.min(BankMenu.HISTORY_PER_PAGE, menu.history().size()); i++) {
                Theme.line(graphics, Layout.GRID_LEFT,
                        BankMenu.HISTORY_TOP + i * BankMenu.HISTORY_STEP - 6, BANK_GRID_WIDTH);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        if (menu.isHistory()) {
            renderHistory(graphics);
        } else {
            renderSummary(graphics);
        }
    }

    /** 主页摘要四行：钱包余额 / 存款本金 / 日利率与手续费 / 距下次结息。 */
    private void renderSummary(GuiGraphics graphics) {
        Theme.text(graphics, Component.translatable("playersuite.bank.line.balance",
                Economy.label(menu.balance())), BankMenu.LEFT, BankMenu.INFO_Y1, Theme.COLOR_TEXT);
        Theme.text(graphics, Component.translatable("playersuite.bank.line.principal",
                Economy.label(menu.principal()), Economy.formatString(menu.maxPrincipal())),
                BankMenu.LEFT, BankMenu.INFO_Y2, Theme.COLOR_TEXT);
        Theme.text(graphics, Component.translatable("playersuite.bank.line.rate",
                BankMenu.percent(menu.rateBps()), BankMenu.percent(menu.feeBps())),
                BankMenu.LEFT, BankMenu.INFO_Y3, Theme.COLOR_TEXT);
        long delay = menu.interestDelay();
        if (delay < 0L) {
            Theme.text(graphics, Component.translatable("playersuite.bank.line.nextOff"),
                    BankMenu.LEFT, BankMenu.INFO_Y4, Theme.COLOR_TEXT_DIM);
        } else {
            Theme.text(graphics, Component.translatable("playersuite.bank.line.next",
                    BankMenu.countdown(delay)), BankMenu.LEFT, BankMenu.INFO_Y4,
                    delay == 0L ? Theme.COLOR_OK : Theme.COLOR_TEXT_DIM);
        }
        Component warn = warning();
        if (warn != null) {
            Theme.rightText(graphics, warn, this.imageWidth, BankMenu.INFO_Y4, Theme.COLOR_WARN);
        }
        if (menu.selfView()) {
            Theme.text(graphics, Component.translatable("playersuite.bank.label.quickDeposit"),
                    BankMenu.LEFT, BankMenu.LABEL_DEPOSIT_Y, Theme.COLOR_TEXT_DIM);
            Theme.text(graphics, Component.translatable("playersuite.bank.label.quickWithdraw"),
                    BankMenu.LEFT, BankMenu.LABEL_WITHDRAW_Y, Theme.COLOR_TEXT_DIM);
        }
    }

    private Component warning() {
        if (menu.frozen()) {
            return Component.translatable(menu.selfView()
                    ? "playersuite.bank.line.frozen" : "playersuite.bank.line.frozenOther");
        }
        if (menu.withdrawDelay() > 0L) {
            return Component.translatable("playersuite.bank.line.cooldown",
                    BankMenu.countdown(menu.withdrawDelay()));
        }
        return null;
    }

    /** 流水页：每条两行（时间 + 金额 / 说明 + 之后的本金）。 */
    private void renderHistory(GuiGraphics graphics) {
        List<BankData.Entry> rows = menu.history();
        if (rows.isEmpty()) {
            Theme.text(graphics, Component.translatable("playersuite.bank.line.noHistory"),
                    BankMenu.LEFT, BankMenu.HISTORY_TOP, Theme.COLOR_TEXT_DIM);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            BankData.Entry entry = rows.get(i);
            int y = BankMenu.HISTORY_TOP + i * BankMenu.HISTORY_STEP;
            Theme.text(graphics, Component.literal(formatTime(entry.time())),
                    BankMenu.LEFT, y, Theme.COLOR_TEXT_DIM);
            Theme.rightText(graphics, signed(entry.amount()), this.imageWidth, y,
                    entry.amount() >= 0L ? Theme.COLOR_OK : Theme.COLOR_WARN);
            Theme.text(graphics, historyLabel(entry.reason()),
                    BankMenu.LEFT, y + BankMenu.HISTORY_LINE2, Theme.COLOR_TEXT);
            Theme.rightText(graphics, Component.translatable("playersuite.bank.line.principalAfter",
                    Economy.label(entry.balance())), this.imageWidth, y + BankMenu.HISTORY_LINE2,
                    Theme.COLOR_TEXT_DIM);
        }
    }

    /** 流水原因短键 -> 文案（短键来自 BankData，只可能是这几个）。 */
    private static Component historyLabel(String reason) {
        if (reason == null || reason.isEmpty()) {
            return Component.translatable("playersuite.bank.history.other");
        }
        return switch (reason) {
            case "deposit" -> Component.translatable("playersuite.bank.history.deposit");
            case "withdraw" -> Component.translatable("playersuite.bank.history.withdraw");
            case "fee" -> Component.translatable("playersuite.bank.history.fee");
            case "interest" -> Component.translatable("playersuite.bank.history.interest");
            case "admin_set" -> Component.translatable("playersuite.bank.history.adminSet");
            case "admin_clear" -> Component.translatable("playersuite.bank.history.adminClear");
            case "freeze" -> Component.translatable("playersuite.bank.history.freeze");
            case "unfreeze" -> Component.translatable("playersuite.bank.history.unfreeze");
            default -> Component.translatable("playersuite.bank.history.other");
        };
    }

    private static Component signed(long amount) {
        if (amount == 0L) {
            return Economy.label(0L);
        }
        return Component.literal((amount > 0L ? "+" : "-") + Economy.formatString(Math.abs(amount)));
    }

    private static String formatTime(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZONE).format(TIME_FORMAT);
    }

    @Override
    protected Component headerRight() {
        return menu.selfView() ? null : Component.translatable("playersuite.bank.line.viewing");
    }

    @Override
    protected Component statusLine() {
        if (menu.isHistory()) {
            return Component.translatable("playersuite.bank.line.historyCount", menu.historyTotal());
        }
        if (!menu.selfView()) {
            return Component.translatable("playersuite.bank.line.adminHint");
        }
        return Component.translatable("playersuite.bank.line.tip",
                Economy.formatString(menu.minDeposit()));
    }
}
