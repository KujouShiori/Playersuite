package dev.mcmods.playersuite.client.checkin;

import dev.mcmods.playersuite.checkin.CheckinMenu;
import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 签到界面：本周期每一天一个只读展示格（已领=绿框 / 漏签=红框 / 未来=灰框 / 今天=黄描边，
 * 每行 9 格，超过 9 天用公共翻页按钮翻页），格子下方一行一天写「第几天 + 状态 + 奖励内容」。
 *
 * <p>按钮只发编号或打开文本框；「能不能签、签的是第几天、补签扣多少、权限够不够」全部在服务端
 * {@code CheckinService} 再判一次。几何常量全部来自 {@link CheckinMenu}，
 * 绘制只用矩形与原版物品图标，不引用任何材质。
 */
public class CheckinScreen extends PageScreen<CheckinMenu> {
    /** 补签面板是纯客户端显示开关（服务端每次都重新校验补签条件，看不见按钮也点不动）。 */
    private boolean makeupPanel;

    private Button checkinButton;
    private Button makeupToggleButton;
    private Button viewOtherButton;
    private Button adminCheckinButton;
    private Button adminResetButton;
    private Button adminStreakButton;
    private final Button[] makeupButtons = new Button[CheckinMenu.DAYS_PER_PAGE];

    /** 奖励文字列的起点（左边留给「第几天 + 状态」，右边留给补签按钮）。 */
    private static final int REWARD_X = 66;
    /** 「第几天 + 状态」列的宽度上限。 */
    private static final int DAY_COL_W = 56;

    public CheckinScreen(CheckinMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, CheckinMenu.CONTENT_ROWS, false);
    }

    @Override
    protected String textInputFeature() {
        return FeatureOpeners.CHECKIN;
    }

    // ---------------------------------------------------------------- 按钮

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        int x0 = left + Layout.GRID_LEFT;
        if (menu.isSelf()) {
            checkinButton = addButton(x0, row1, CheckinMenu.BUTTON_W,
                    Component.translatable("playersuite.checkin.button.checkin"),
                    button -> sendButton(CheckinMenu.BTN_CHECKIN));
            int x = x0 + CheckinMenu.BUTTON_STEP;
            if (menu.makeupAllowed() && menu.makeupMax() > 0) {
                makeupToggleButton = addButton(x, row1, CheckinMenu.BUTTON_W,
                        Component.translatable("playersuite.checkin.button.makeupMode"),
                        button -> {
                            makeupPanel = !makeupPanel;
                            syncMakeupButtons();
                        });
                x += CheckinMenu.BUTTON_STEP;
            }
            if (menu.hasFlag(CheckinMenu.FLAG_MANAGE)) {
                viewOtherButton = addButton(x, row1, CheckinMenu.BUTTON_W,
                        Component.translatable("playersuite.checkin.button.viewOther"),
                        button -> openTextInput(CHECKIN_ACTION_TARGET,
                                Component.translatable("playersuite.checkin.prompt.player"), "", false));
            }
        } else {
            addButton(x0, row1, CheckinMenu.BUTTON_W,
                    Component.translatable("playersuite.checkin.button.self"),
                    button -> sendButton(CheckinMenu.BTN_BACK_TO_SELF));
            if (menu.admin()) {
                int x = x0 + CheckinMenu.BUTTON_STEP;
                adminCheckinButton = addButton(x, row1, CheckinMenu.BUTTON_W,
                        Component.translatable("playersuite.checkin.button.adminCheckin"),
                        button -> sendButton(CheckinMenu.BTN_ADMIN_CHECKIN));
                x += CheckinMenu.BUTTON_STEP;
                adminResetButton = addButton(x, row1, CheckinMenu.BUTTON_W,
                        Component.translatable("playersuite.checkin.button.adminReset"),
                        button -> sendButton(CheckinMenu.BTN_ADMIN_RESET));
                // 强制设定连签走文本输入框（numeric），放在内容区第一行右侧，避免挤掉底部三个按钮
                adminStreakButton = addButton(left + 122, top + CheckinMenu.INFO_Y1 - 1, 46, 11,
                        Component.translatable("playersuite.checkin.button.adminStreak"),
                        button -> openTextInput(CHECKIN_ACTION_STREAK,
                                Component.translatable("playersuite.checkin.prompt.streak", menu.streak()),
                                Integer.toString(menu.streak()), true));
            }
        }
        // 补签行按钮：每行一个，默认全部隐藏，点开「补签模式」后只给能补的天显示出来
        for (int i = 0; i < CheckinMenu.DAYS_PER_PAGE; i++) {
            final int row = i;
            makeupButtons[i] = addButton(left + CheckinMenu.MAKEUP_X,
                    top + CheckinMenu.TEXT_TOP + row * CheckinMenu.TEXT_PITCH - 1,
                    CheckinMenu.MAKEUP_W, CheckinMenu.MAKEUP_H,
                    Component.translatable("playersuite.checkin.button.makeup"),
                    button -> {
                        int day = menu.rowDay(row);
                        if (day >= 0) {
                            sendButton(CheckinMenu.BTN_MAKEUP_BASE + day);
                        }
                    });
            makeupButtons[i].visible = false;
        }
        syncMakeupButtons();
    }

    /** 文本输入动作名，必须与服务端 {@code CheckinService.ACTION_*} 一致。 */
    private static final String CHECKIN_ACTION_TARGET = "target";
    private static final String CHECKIN_ACTION_STREAK = "admin_streak";

    /** 补签面板打开时，才给「已错过且没领、次数还够」的那几行挂出按钮；同时刷新两个按钮的状态。 */
    private void syncMakeupButtons() {
        if (checkinButton != null) {
            checkinButton.active = !menu.todayClaimed();
        }
        if (makeupToggleButton != null) {
            makeupToggleButton.setMessage(Component.translatable(makeupPanel
                    ? "playersuite.checkin.button.makeupOff" : "playersuite.checkin.button.makeupMode"));
        }
        for (int i = 0; i < CheckinMenu.DAYS_PER_PAGE; i++) {
            Button button = makeupButtons[i];
            if (button != null) {
                button.visible = makeupPanel && menu.isSelf() && i < menu.rows()
                        && menu.rowHas(i, CheckinMenu.ROW_MAKEUPABLE);
            }
        }
    }

    private Button addButton(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(label, onPress).bounds(x, y, width, height).build());
    }

    private Button addButton(int x, int y, int width, Component label, Button.OnPress onPress) {
        return addButton(x, y, width, CheckinMenu.BUTTON_H, label, onPress);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(getGuiLeft(), getGuiTop(), 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        // 一行 9 格的图标底（每格对应的奖励物品由只读展示槽画在上面）
        Theme.slotRow(graphics, CheckinMenu.GRID_TOP, 1, CheckinMenu.DAYS_PER_PAGE);
        for (int i = 0; i < menu.rows(); i++) {
            int x = CheckinMenu.slotX(i);
            int y = CheckinMenu.GRID_TOP;
            if (!menu.rowHas(i, CheckinMenu.ROW_CLAIMED) && menu.rowHas(i, CheckinMenu.ROW_PAST)) {
                // 漏签的那格涂一层暗红底，扫一眼就知道哪几天没签
                graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2B1D21);
            }
            drawFrame(graphics, x, y, stateColor(i));
        }
        graphics.pose().popPose();
    }

    /** 1 像素矩形描边（不依赖任何材质）。 */
    private static void drawFrame(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + Layout.PITCH, y + 1, color);
        graphics.fill(x, y + Layout.PITCH - 1, x + Layout.PITCH, y + Layout.PITCH, color);
        graphics.fill(x, y, x + 1, y + Layout.PITCH, color);
        graphics.fill(x + Layout.PITCH - 1, y, x + Layout.PITCH, y + Layout.PITCH, color);
    }

    private int stateColor(int row) {
        if (menu.rowHas(row, CheckinMenu.ROW_TODAY)) {
            return CheckinMenu.COLOR_TODAY;                 // 今天：描边
        }
        if (menu.rowHas(row, CheckinMenu.ROW_CLAIMED)) {
            return CheckinMenu.COLOR_CLAIMED;               // 已领：绿色高亮
        }
        if (menu.rowHas(row, CheckinMenu.ROW_PAST)) {
            return CheckinMenu.COLOR_MISSED;                // 漏签：红
        }
        return CheckinMenu.COLOR_FUTURE;                    // 未到期：灰
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        Font font = this.font;
        // 格子里的「第几天」角标
        for (int i = 0; i < menu.rows(); i++) {
            int day = menu.rowDay(i);
            if (day >= 0) {
                graphics.drawString(font, Integer.toString(day + 1),
                        CheckinMenu.slotX(i) + 12, CheckinMenu.GRID_TOP + 10, stateColor(i), true);
            }
        }
        // 右侧有「设定连签」小按钮时，第一行要收窄，避免文字压到按钮下面
        int infoW = !menu.isSelf() && menu.admin() ? 112 : 158;
        Theme.text(graphics, ellipsis(font,
                        Component.translatable("playersuite.checkin.line.day",
                                menu.todayIndex() + 1, menu.cycleDays(), menu.claimedCount()), infoW),
                Layout.GRID_LEFT, CheckinMenu.INFO_Y1, Theme.COLOR_TEXT);
        if (menu.todayClaimed()) {
            // 今日是否已签：已签时把「今天可得」换成「今天已入账」，一眼就能看出来
            Theme.text(graphics, Component.translatable("playersuite.checkin.line.todayDone",
                            Economy.label(menu.todayTotal())),
                    Layout.GRID_LEFT, CheckinMenu.INFO_Y2, Theme.COLOR_TEXT_DIM);
        } else {
            Theme.text(graphics, Component.translatable("playersuite.checkin.line.today",
                            Economy.label(menu.todayTotal()), Economy.label(menu.todayBonus())),
                    Layout.GRID_LEFT, CheckinMenu.INFO_Y2, Theme.COLOR_OK);
        }
        if (!menu.makeupAllowed() || menu.makeupMax() <= 0) {
            Theme.text(graphics, Component.translatable("playersuite.checkin.line.makeupOff"),
                    Layout.GRID_LEFT, CheckinMenu.INFO_Y3, Theme.COLOR_TEXT_DIM);
        } else {
            Theme.text(graphics, Component.translatable("playersuite.checkin.line.makeup",
                            menu.makeupLeft(), menu.makeupMax(), Economy.formatString(menu.makeupCost())),
                    Layout.GRID_LEFT, CheckinMenu.INFO_Y3,
                    menu.makeupLeft() > 0 ? Theme.COLOR_TEXT : Theme.COLOR_WARN);
        }
        for (int i = 0; i < menu.rows(); i++) {
            drawDayLine(graphics, font, i);
        }
    }

    /** 一行：第 N 天 + 状态词 + 奖励内容（金币 + 物品名x数量，物品名由客户端本地化）。 */
    private void drawDayLine(GuiGraphics graphics, Font font, int row) {
        int day = menu.rowDay(row);
        if (day < 0) {
            return;
        }
        int y = CheckinMenu.TEXT_TOP + row * CheckinMenu.TEXT_PITCH;
        int color = lineColor(row);
        Theme.text(graphics, ellipsis(font,
                        Component.translatable("playersuite.checkin.row.day", day + 1, statusText(row)),
                        DAY_COL_W),
                CheckinMenu.TEXT_X, y, color);
        MutableComponent reward = Component.literal(Economy.formatString(menu.rowCoin(row)));
        int count = menu.rowCount(row);
        if (count > 0) {
            ItemStack display = menu.rowDisplay(row);
            Component name = display.isEmpty()
                    ? Component.translatable("playersuite.checkin.row.itemUnknown")
                    : display.getHoverName();
            reward.append(Component.translatable("playersuite.checkin.row.item", count, name));
        }
        Theme.text(graphics, ellipsis(font, reward, rewardWidth()), REWARD_X, y, color);
    }

    /** 补签面板打开时右侧要留给按钮，奖励文字相应收窄（避免压在按钮下面）。 */
    private int rewardWidth() {
        int right = makeupPanel && menu.isSelf()
                ? CheckinMenu.MAKEUP_X - 2
                : Layout.PANEL_WIDTH - Layout.GRID_LEFT;
        return Math.max(10, right - REWARD_X);
    }

    private int lineColor(int row) {
        if (menu.rowHas(row, CheckinMenu.ROW_CLAIMED)) {
            return Theme.COLOR_OK;
        }
        if (menu.rowHas(row, CheckinMenu.ROW_TODAY)) {
            return Theme.COLOR_TEXT;
        }
        if (menu.rowHas(row, CheckinMenu.ROW_PAST)) {
            return Theme.COLOR_WARN;
        }
        return Theme.COLOR_TEXT_DIM;
    }

    private Component statusText(int row) {
        if (menu.rowHas(row, CheckinMenu.ROW_CLAIMED)) {
            return Component.translatable("playersuite.checkin.row.claimed").withStyle(ChatFormatting.GREEN);
        }
        if (menu.rowHas(row, CheckinMenu.ROW_TODAY)) {
            return menu.todayClaimed()
                    ? Component.translatable("playersuite.checkin.row.todayDone").withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("playersuite.checkin.row.today").withStyle(ChatFormatting.YELLOW);
        }
        if (menu.rowHas(row, CheckinMenu.ROW_PAST)) {
            return Component.translatable("playersuite.checkin.row.missed").withStyle(ChatFormatting.RED);
        }
        return Component.translatable("playersuite.checkin.row.future").withStyle(ChatFormatting.GRAY);
    }

    @Override
    protected Component headerRight() {
        return Component.translatable("playersuite.checkin.header.streak", menu.streak());
    }

    @Override
    protected Component statusLine() {
        if (!menu.isSelf()) {
            return Component.translatable("playersuite.checkin.line.viewing");
        }
        return Component.translatable("playersuite.checkin.line.next",
                Economy.formatString(menu.remainingCoin()),
                Economy.formatString(menu.nextCycleCoin()),
                countdown(menu.nextResetMs()));
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (viewOtherButton != null && viewOtherButton.visible && viewOtherButton.isHovered()) {
            graphics.renderComponentTooltip(this.font,
                    List.<Component>of(Component.translatable("playersuite.checkin.button.viewOther.hint")),
                    mouseX, mouseY);
            return;
        }
        if (adminStreakButton != null && adminStreakButton.visible && adminStreakButton.isHovered()) {
            graphics.renderComponentTooltip(this.font,
                    List.<Component>of(Component.translatable("playersuite.checkin.button.adminStreak.hint")),
                    mouseX, mouseY);
        }
    }

    /** 毫秒 -> {@code 3h12m}（语言中立的倒计时，与银行页同一口径）。 */
    private static String countdown(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long seconds = millis / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0L) {
            return hours + "h" + (minutes % 60L) + "m";
        }
        return minutes + "m";
    }

    /** 按像素宽度截断（中文物品名可能很长，别把整行撑破）。 */
    private static Component ellipsis(Font font, Component text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        String raw = text.getString();
        int dots = font.width("...");
        int cut = raw.length();
        while (cut > 0 && font.width(raw.substring(0, cut)) + dots > maxWidth) {
            cut--;
        }
        return Component.literal(raw.substring(0, Math.max(0, cut)) + "...");
    }
}
