package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.DisplaySlots;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 签到容器：本周期每一天一格（只读展示槽显示当天奖励物品），下方一行一天写奖励文字。
 *
 * <pre>
 *   y=21..39   9 个展示槽（一行 9 天；周期超过 9 天用公共翻页按钮翻页）
 *   y=41/52/63 三行状态文字（第几天 / 今天能领什么 / 补签余量）
 *   y=74..     本页每一天一行：第 N 天 + 状态 + 奖励内容（补签模式下右侧出现「补签」小按钮）
 *   y=191      底部：签到 / 补签模式切换 /（管理时）查看他人·代签·重置
 * </pre>
 *
 * <p>界面几何常量全部放在这里，{@code CheckinScreen} 直接引用，保证服务端与客户端不错位。
 * 状态（谁已领、谁是今天、谁能补签）全部由服务端算好后写进 {@code writeCheckinData}，
 * 客户端只负责画与发按钮编号。
 *
 * <p>公共头之后的附加数据顺序见 {@link #writeCheckinData}，客户端构造函数必须逐字段对齐读取。
 */
public class CheckinMenu extends PageMenu {

    // ---------------------------------------------------------------- 按钮编号

    /** 「签到」（公共 0-9 之后，从 BTN_CUSTOM=10 起）。 */
    public static final int BTN_CHECKIN = BTN_CUSTOM;
    /** 补签第 N 天（周期内 0 起）= BTN_MAKEUP_BASE + N；周期最多 31 天，占到 48。 */
    public static final int BTN_MAKEUP_BASE = BTN_CUSTOM + 8;
    /** 管理：代签目标玩家当天（adminPermission）。 */
    public static final int BTN_ADMIN_CHECKIN = BTN_CUSTOM + 60;
    /** 管理：重置目标玩家的连续天数与补签次数（adminPermission）。 */
    public static final int BTN_ADMIN_RESET = BTN_CUSTOM + 61;
    /** 管理：从「别人的页」返回「自己的页」（只是换目标重开，不带任何破坏性动作）。 */
    public static final int BTN_BACK_TO_SELF = BTN_CUSTOM + 62;

    // ---------------------------------------------------------------- 标志位

    /** 看的是自己的签到页（只有自己的页面才允许签到/补签）。 */
    public static final int FLAG_SELF = 1;
    /** 允许补签（配置 allowMakeup 且 makeupMax > 0）。 */
    public static final int FLAG_MAKEUP_ALLOWED = 2;
    /** managePermission：可以查看他人（界面才出现「查看他人」输入框）。 */
    public static final int FLAG_MANAGE = 4;
    /** adminPermission：代签 / 重置 / 强制设定连签。 */
    public static final int FLAG_ADMIN = 8;
    /** 今天已经签过了（服务端算好，客户端只用来置灰「签到」按钮）。 */
    public static final int FLAG_TODAY_CLAIMED = 16;

    // ---------------------------------------------------------------- 行状态位

    public static final int ROW_CLAIMED = 1;
    public static final int ROW_TODAY = 2;
    public static final int ROW_PAST = 4;
    public static final int ROW_MAKEUPABLE = 8;

    // ---------------------------------------------------------------- 界面几何（面板相对坐标）

    /** 内容区行数（决定底部按钮行与面板高度；与 CheckinScreen 构造一致）。 */
    public static final int CONTENT_ROWS = 8;
    /** 每页 9 天，与 Layout 一行 9 格对齐。 */
    public static final int DAYS_PER_PAGE = CheckinService.DAYS_PER_PAGE;
    public static final int GRID_LEFT = Layout.GRID_LEFT;          // 8
    public static final int GRID_TOP = Layout.contentTop();        // 21
    public static final int PITCH = Layout.PITCH;                  // 18
    public static final int INFO_Y1 = 41;
    public static final int INFO_Y2 = 52;
    public static final int INFO_Y3 = 63;
    public static final int TEXT_TOP = 74;
    public static final int TEXT_PITCH = 10;
    public static final int TEXT_X = 8;
    public static final int MAKEUP_X = 138;
    public static final int MAKEUP_W = 30;
    public static final int MAKEUP_H = 10;
    public static final int BUTTON_W = 50;
    public static final int BUTTON_STEP = 54;
    public static final int BUTTON_H = 20;
    /** 「今天」用描边、已领用绿色、漏签用灰/红，客户端直接引用这三个颜色。 */
    public static final int COLOR_TODAY = 0xFFFFD873;
    public static final int COLOR_CLAIMED = 0xFF63C863;
    public static final int COLOR_MISSED = 0xFFC86363;
    public static final int COLOR_FUTURE = 0xFF5A6070;

    // ---------------------------------------------------------------- 客户端数据

    private final int cycleDays;
    private final int todayIndex;
    private final int streak;
    private final int claimedCount;
    private final int makeupUsed;
    private final int makeupMax;
    private final long makeupCost;
    private final long nextResetMs;
    private final long remainingCoin;
    private final long nextCycleCoin;
    private final long todayTotal;
    private final long todayBonus;
    private final int flags;
    private final int rows;
    private final int[] rowDay;
    private final long[] rowCoin;
    private final int[] rowCount;
    private final int[] rowFlags;

    /** 服务端专用：被查看的玩家；客户端为 null。 */
    private final ServerPlayer target;

    // ---------------------------------------------------------------- 构造

    /** 客户端构造：参数顺序固定 (containerId, inventory, extraData)。 */
    public CheckinMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(CheckinMenus.CHECKIN_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        this.target = null;
        this.cycleDays = Math.max(1, buf.readInt());
        this.todayIndex = buf.readInt();
        this.streak = Math.max(0, buf.readInt());
        this.claimedCount = Math.max(0, buf.readInt());
        this.makeupUsed = Math.max(0, buf.readInt());
        this.makeupMax = Math.max(0, buf.readInt());
        this.makeupCost = Math.max(0L, buf.readLong());
        this.nextResetMs = Math.max(0L, buf.readLong());
        this.flags = buf.readInt();
        this.rows = Math.max(0, Math.min(DAYS_PER_PAGE, buf.readInt()));
        this.remainingCoin = Math.max(0L, buf.readLong());
        this.nextCycleCoin = Math.max(0L, buf.readLong());
        this.todayTotal = Math.max(0L, buf.readLong());
        this.todayBonus = Math.max(0L, buf.readLong());
        this.rowDay = new int[rows];
        this.rowCoin = new long[rows];
        this.rowCount = new int[rows];
        this.rowFlags = new int[rows];
        for (int i = 0; i < rows; i++) {
            this.rowDay[i] = buf.readInt();
            this.rowCoin[i] = Math.max(0L, buf.readLong());
            this.rowCount[i] = Math.max(0, buf.readInt());
            this.rowFlags[i] = buf.readInt();
            // 图标由服务端的只读展示槽同步过来，这里先占位（拿不出来，也不会刷物品）
            addSlot(DisplaySlots.empty(slotX(i), GRID_TOP));
        }
    }

    /** 服务端构造：奖励快照 + 展示槽都在这一条路径上生成，保证写入与槽位一致。 */
    public CheckinMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer target,
                       int page, int pages, boolean manage, int flags) {
        super(CheckinMenus.CHECKIN_MENU.get(), containerId, inventory, viewer, page, pages, manage);
        this.target = target;
        long today = CheckinService.currentDay();
        CheckinData data = CheckinService.data(target);
        this.cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        this.todayIndex = data.dayIndex(today, cycleDays);
        this.streak = CheckinService.effectiveStreak(data, today);
        this.claimedCount = data.claimedCount(cycleDays);
        this.makeupUsed = data.makeupUsed();
        this.makeupMax = SuiteConfig.checkinMakeupMax();
        this.makeupCost = Math.max(0L, SuiteConfig.checkinMakeupCost());
        this.nextResetMs = CheckinService.millisToReset();
        this.remainingCoin = CheckinService.remainingCoin(data, today);
        this.nextCycleCoin = CheckinService.cycleCoinTotal();
        this.todayBonus = CheckinService.streakBonus(Math.min(CheckinData.MAX_STREAK, streak + 1));
        this.todayTotal = CheckinService.coinWithBonus(todayIndex, Math.min(CheckinData.MAX_STREAK, streak + 1));
        int claimedToday = data.lastDay() == today || data.isClaimed(todayIndex) ? FLAG_TODAY_CLAIMED : 0;
        this.flags = ((flags & ~FLAG_SELF) | (CheckinService.isSamePlayer(viewer, target) ? FLAG_SELF : 0))
                | claimedToday;
        List<CheckinService.DayView> view = CheckinService.pageView(data, page, today);
        this.rows = view.size();
        this.rowDay = new int[rows];
        this.rowCoin = new long[rows];
        this.rowCount = new int[rows];
        this.rowFlags = new int[rows];
        for (int i = 0; i < rows; i++) {
            CheckinService.DayView day = view.get(i);
            this.rowDay[i] = day.index();
            this.rowCoin[i] = day.coin();
            this.rowCount[i] = day.itemCount();
            this.rowFlags[i] = rowFlagsOf(day);
            addSlot(DisplaySlots.of(day.icon(), slotX(i), GRID_TOP));
        }
    }

    private static int rowFlagsOf(CheckinService.DayView day) {
        int value = 0;
        if (day.claimed()) {
            value |= ROW_CLAIMED;
        }
        if (day.today()) {
            value |= ROW_TODAY;
        }
        if (day.past()) {
            value |= ROW_PAST;
        }
        if (day.makeupable()) {
            value |= ROW_MAKEUPABLE;
        }
        return value;
    }

    /**
     * 写 openMenu 的附加数据（顺序必须与客户端构造函数逐字段一致）：
     * 公共头 -> cycleDays / todayIndex / streak / claimedCount / makeupUsed / makeupMax /
     * makeupCost / nextResetMs / flags / rows / remainingCoin / nextCycleCoin /
     * todayTotal / todayBonus -> 每行 (dayIndex, coin, itemCount, rowFlags)。
     */
    public static void writeCheckinData(RegistryFriendlyByteBuf buf, ServerPlayer viewer, ServerPlayer target,
                                        int page, int pages, boolean manage, int flags) {
        long today = CheckinService.currentDay();
        CheckinData data = CheckinService.data(target);
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        List<CheckinService.DayView> view = CheckinService.pageView(data, page, today);
        int todayIndex = data.dayIndex(today, cycleDays);
        int streak = Math.min(CheckinData.MAX_STREAK,
                Math.max(0, CheckinService.effectiveStreak(data, today)) + 1);
        int claimedToday = data.lastDay() == today || data.isClaimed(todayIndex) ? FLAG_TODAY_CLAIMED : 0;
        int effective = ((flags & ~FLAG_SELF)
                | (CheckinService.isSamePlayer(viewer, target) ? FLAG_SELF : 0)) | claimedToday;
        writeHeader(buf, Economy.balance(viewer), page, pages, manage, 0);
        buf.writeInt(cycleDays);
        buf.writeInt(data.dayIndex(today, cycleDays));
        buf.writeInt(CheckinService.effectiveStreak(data, today));
        buf.writeInt(data.claimedCount(cycleDays));
        buf.writeInt(data.makeupUsed());
        buf.writeInt(SuiteConfig.checkinMakeupMax());
        buf.writeLong(Math.max(0L, SuiteConfig.checkinMakeupCost()));
        buf.writeLong(CheckinService.millisToReset());
        buf.writeInt(effective);
        buf.writeInt(view.size());
        buf.writeLong(CheckinService.remainingCoin(data, today));
        buf.writeLong(CheckinService.cycleCoinTotal());
        buf.writeLong(CheckinService.coinWithBonus(todayIndex, streak));
        buf.writeLong(CheckinService.streakBonus(streak));
        for (CheckinService.DayView day : view) {
            buf.writeInt(day.index());
            buf.writeLong(day.coin());
            buf.writeInt(day.itemCount());
            buf.writeInt(rowFlagsOf(day));
        }
    }

    /** 第 i 个格子的 x（每行 9 个）。 */
    public static int slotX(int i) {
        return GRID_LEFT + Math.max(0, Math.min(DAYS_PER_PAGE - 1, i)) * PITCH;
    }

    // ---------------------------------------------------------------- 按钮

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (buttonId == BTN_BACK_TO_SELF) {
            CheckinService.open(viewer, viewer, -1);
            return true;
        }
        if (buttonId == BTN_CHECKIN) {
            // 看他人页面上的「签到」根本不显示，但按钮号仍然要二次校验
            if (!isSelf()) {
                tellSelf(viewer, "playersuite.checkin.msg.otherView");
                return false;
            }
            if (CheckinService.checkin(viewer)) {
                CheckinService.refresh(viewer);
            }
            return true;
        }
        if (buttonId == BTN_ADMIN_CHECKIN) {
            if (CheckinService.adminCheckin(viewer, target)) {
                CheckinService.refresh(viewer);
            }
            return true;
        }
        if (buttonId == BTN_ADMIN_RESET) {
            if (CheckinService.adminReset(viewer, target)) {
                CheckinService.refresh(viewer);
            }
            return true;
        }
        if (buttonId >= BTN_MAKEUP_BASE && buttonId < BTN_MAKEUP_BASE + CheckinData.MAX_CYCLE_DAYS) {
            if (!isSelf()) {
                tellSelf(viewer, "playersuite.checkin.msg.otherView");
                return false;
            }
            int index = buttonId - BTN_MAKEUP_BASE;
            if (index >= 0 && index < cycleDays) {
                if (CheckinService.makeup(viewer, index)) {
                    CheckinService.refresh(viewer);
                }
            }
            return true;
        }
        return false;
    }

    private void tellSelf(ServerPlayer viewer, String key) {
        viewer.displayClientMessage(Component.translatable(key), false);
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        CheckinService.open(viewer, target == null ? viewer : target, page);
    }

    /** 服务端：被查看的玩家；客户端为 null。 */
    public ServerPlayer target() {
        return target;
    }

    // ---------------------------------------------------------------- 只读访问

    public int cycleDays() {
        return cycleDays;
    }

    public int todayIndex() {
        return todayIndex;
    }

    public int streak() {
        return streak;
    }

    public int claimedCount() {
        return claimedCount;
    }

    public int makeupUsed() {
        return makeupUsed;
    }

    public int makeupMax() {
        return makeupMax;
    }

    public long makeupCost() {
        return makeupCost;
    }

    public long nextResetMs() {
        return nextResetMs;
    }

    public long remainingCoin() {
        return remainingCoin;
    }

    public long nextCycleCoin() {
        return nextCycleCoin;
    }

    /** 今天签到能拿到的金币合计（基础 + 按下一档连签算的加成）。 */
    public long todayTotal() {
        return todayTotal;
    }

    /** 今天那笔里的连签加成部分。 */
    public long todayBonus() {
        return todayBonus;
    }

    /** 今天是否已经签过（服务端算好的标志，客户端不参与判定）。 */
    public boolean todayClaimed() {
        return hasFlag(FLAG_TODAY_CLAIMED);
    }

    public int flags() {
        return flags;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean isSelf() {
        return hasFlag(FLAG_SELF);
    }

    public boolean admin() {
        return hasFlag(FLAG_ADMIN);
    }

    public boolean makeupAllowed() {
        return hasFlag(FLAG_MAKEUP_ALLOWED);
    }

    /** 剩余补签次数（服务端算好，客户端只做显示）。 */
    public int makeupLeft() {
        return Math.max(0, makeupMax - makeupUsed);
    }

    public int rows() {
        return rows;
    }

    public int rowDay(int row) {
        return row >= 0 && row < rows ? rowDay[row] : -1;
    }

    public long rowCoin(int row) {
        return row >= 0 && row < rows ? rowCoin[row] : 0L;
    }

    public int rowCount(int row) {
        return row >= 0 && row < rows ? rowCount[row] : 0;
    }

    public int rowFlags(int row) {
        return row >= 0 && row < rows ? rowFlags[row] : 0;
    }

    public boolean rowHas(int row, int bit) {
        return (rowFlags(row) & bit) != 0;
    }

    /** 第 row 天的展示物品（来自只读展示槽，永远拿不出来）。 */
    public ItemStack rowDisplay(int row) {
        if (row < 0 || row >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(row).getItem();
    }
}
