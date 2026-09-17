package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleMenuProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 每日签到的业务中枢：签到 / 补签 / 连续签到加成 / OP 代签与重置 / 界面打开入口。
 *
 * <p><b>所有方法只在逻辑服务端调用</b>；界面按钮只发编号，天序号、金额、权限、
 * 「今天到底签没签」全部在这里重新判定（客户端说什么都不算）。
 *
 * <p><b>一天的定义</b>：见 {@link #currentDay()}，以服务器本地时区 +
 * {@code SuiteConfig.checkinResetHour()} 为分界线，返回 epochDay 整数。
 * 全功能只用这一个函数判断「是否新的一天」和「本周期第几天」，
 * 不允许别处再各自 {@code System.currentTimeMillis()/86400000}。
 */
public final class CheckinService {
    /** 每页显示几天（与 {@code CheckinMenu.DAYS_PER_PAGE}、界面一行 9 个格子一致）。 */
    public static final int DAYS_PER_PAGE = 9;

    /** 一天的毫秒数（只用于把 epochDay 还原成日期文本，不参与「是否新的一天」判定）。 */
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /**
     * 服务器默认时区。<b>签到是服务端权威</b>：分界线按服务器本地时区算，
     * 客户端改自己的系统时间、换设备都不会影响判定；换服务器时区会让所有玩家的分界一起平移。
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    // ---- 文本输入动作名（InputRouter 接线后由 {@link #onTextInput} 分发） ----
    /** managePermission：输入玩家名查看他人签到页。 */
    public static final String ACTION_TARGET = "target";
    /** adminPermission：给当前查看的玩家强制设定连续天数（numeric=true）。 */
    public static final String ACTION_ADMIN_STREAK = "admin_streak";

    /** 金币收支的流水原因短键（显示成 {@code txn.playersuite.checkin} / {@code ..._makeup}）。 */
    public static final String REASON_CHECKIN = "checkin";
    /** 补签扣费用的流水短键（{@code txn.playersuite.checkin_makeup}）。 */
    public static final String REASON_MAKEUP = "checkin_makeup";

    // ---- 物品奖励配置缓存（只在服务端线程访问，配置串变了才重解析） ----
    private static String cachedRaw;
    private static Map<Integer, List<ItemStack>> cachedRewards = Map.of();

    private CheckinService() {
    }

    // ---------------------------------------------------------------- 时间

    /**
     * 当前是「第几天」（epochDay 整数，1970-01-01 起算）。
     *
     * <p>分界线由 {@code SuiteConfig.checkinResetHour()} 决定：先把服务器本地时间<b>回拨
     * resetHour 小时</b>再取自然日。于是 {@code resetHour = 6} 时，今天 00:00~05:59 仍然算
     * 「昨天」，早上 6:00 一到才进入新的一天（{@code resetHour = 0} 就是普通的午夜分界）。
     *
     * <p>时区固定 {@link ZoneId#systemDefault()}（服务器默认时区），因为签到奖励由服务端发放，
     * 必须以服务器日历为准；客户端只做展示，展示用的日期文本同样由服务端下发。
     */
    public static long currentDay() {
        int resetHour = Math.max(0, Math.min(23, SuiteConfig.checkinResetHour()));
        return LocalDateTime.now(ZONE).minusHours(resetHour).toLocalDate().toEpochDay();
    }

    /** epochDay -> {@code yyyy-MM-dd}（服务器时区口径，给管理端提示与日志用）。 */
    public static String formatDay(long epochDay) {
        if (epochDay <= 0L) {
            return "-";
        }
        return LocalDate.ofEpochDay(epochDay).toString();
    }

    /** 「距下一次每日重置还剩多少毫秒」（客户端展示用；上限一天）。 */
    public static long millisToReset() {
        int resetHour = Math.max(0, Math.min(23, SuiteConfig.checkinResetHour()));
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime next = now.toLocalDate().atTime(resetHour, 0);
        if (!now.isBefore(next)) {
            next = next.plusDays(1L);
        }
        long delta = java.time.Duration.between(now, next).toMillis();
        return Math.max(0L, Math.min(DAY_MS, delta));
    }

    // ---------------------------------------------------------------- 数据

    /** 读附件并做一次「周期滚动 + 合法性夹取」；有变化立刻 setData 触发脏标记（否则不落盘）。 */
    public static CheckinData data(Player player) {
        CheckinData data = player.getData(CheckinAttachments.CHECKIN);
        boolean dirty = data.rollTo(SuiteConfig.checkinCycleDays(), currentDay());
        if (dirty) {
            save(player, data);
        }
        return data;
    }

    /** 附件对象是可变的，改完必须 setData 同一个实例才能落盘。 */
    public static void save(Player player, CheckinData data) {
        player.setData(CheckinAttachments.CHECKIN, data);
    }

    /**
     * 对外显示的连续天数：只要「上次签到是昨天或今天」就认，否则视为已断（显示 0）。
     *
     * <p>故意不在读取时改写存档：断签的玩家下一次签到会自动重算成 1，
     * 但读取本身不产生写盘（避免每次开界面都脏一次档）。
     */
    public static int effectiveStreak(CheckinData data, long today) {
        if (data == null || data.streak() <= 0) {
            return 0;
        }
        long last = data.lastDay();
        return (last == today || last == today - 1L) ? data.streak() : 0;
    }

    /** 今天是否已经签到（本人签到或管理员代签都算）。 */
    public static boolean isCheckedInToday(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return false;
        }
        CheckinData data = data(player);
        long today = currentDay();
        return data.lastDay() == today || data.isClaimed(data.dayIndex(today, SuiteConfig.checkinCycleDays()));
    }

    // ---------------------------------------------------------------- 奖励

    /** 本周期第 index（0 起）天的基础金币（金币表不足天数时用最后一个值补齐，与配置注释一致）。 */
    public static long coinForDay(int index) {
        long[] table = SuiteConfig.checkinCoinRewards();
        if (table == null || table.length == 0) {
            return 0L;
        }
        int i = Math.max(0, Math.min(index, table.length - 1));
        return Math.max(0L, table[i]);
    }

    /**
     * 连续签到加成：{@code min(连签天数 * streakBonus, streakBonusMax)}，再夹一次钱包上限。
     *
     * <p>{@code streak} 先夹到 {@link CheckinData#MAX_STREAK}，乘积最大 1e5 * 1e6 = 1e11，
     * 离 long 上限还差五个数量级，不会溢出。
     */
    public static long streakBonus(int streak) {
        long per = SuiteConfig.checkinStreakBonus();
        long cap = SuiteConfig.checkinStreakBonusMax();
        if (per <= 0L || cap <= 0L || streak <= 0) {
            return 0L;
        }
        long value = per * (long) Math.max(0, Math.min(CheckinData.MAX_STREAK, streak));
        return Math.min(Math.min(value, cap), SuiteConfig.maxBalance());
    }

    /** 第 index 天「按连签 streak 计算」的金币合计（防溢出：先夹加成再相加）。 */
    public static long coinWithBonus(int index, int streak) {
        long base = coinForDay(index);
        long bonus = streakBonus(streak);
        long cap = SuiteConfig.maxBalance();
        if (base > cap) {
            base = cap;
        }
        if (bonus > cap - base) {
            bonus = cap - base;
        }
        return Math.max(0L, base + bonus);
    }

    /**
     * 解析 {@code SuiteConfig.checkinItemRewards()}：返回「周期第几天（1 起）-> 该天物品奖励」。
     *
     * <p>推荐格式（与配置注释同源，逐条容错，坏项直接跳过）：
     * <pre>3:minecraft:diamond|1;7:minecraft:netherite_scrap|1</pre>
     * 分隔符与写法都宽松处理：条目之间 {@code ;} 或 {@code ,}；数量前面 {@code |} 或 {@code :}；
     * 物品 id 可以省略命名空间（{@code 3:diamond|1} 等价于 {@code minecraft:diamond}）；
     * 同一天可以写多条（会合并）；不写数量按 1 算。
     */
    public static Map<Integer, List<ItemStack>> itemRewardTable() {
        String raw = SuiteConfig.checkinItemRewards();
        if (raw == null) {
            raw = "";
        }
        if (!raw.equals(cachedRaw)) {
            cachedRaw = raw;
            cachedRewards = parseItemRewards(raw, SuiteConfig.checkinCycleDays());
        }
        return cachedRewards;
    }

    /** 第 index（0 起）天的物品奖励副本（每次调用都新建，绝不把缓存对象发出去）。 */
    public static List<ItemStack> itemsForDay(int index) {
        List<ItemStack> out = new ArrayList<>();
        List<ItemStack> protos = itemRewardTable().get(index + 1);
        if (protos == null) {
            return out;
        }
        for (ItemStack proto : protos) {
            if (!proto.isEmpty()) {
                out.add(proto.copy());
            }
        }
        return out;
    }

    /** 实际解析：坏条目跳过并打一条 warn（同一次配置内容只提醒一次），绝不让界面或签到崩掉。 */
    private static Map<Integer, List<ItemStack>> parseItemRewards(String raw, int cycleDays) {
        Map<Integer, List<ItemStack>> table = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return table;
        }
        int days = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, cycleDays));
        for (String piece : raw.split("[,;]")) {
            String entry = piece.trim();
            if (entry.isEmpty()) {
                continue;
            }
            ItemStack stack = parseEntry(entry);
            if (stack.isEmpty()) {
                continue;
            }
            int day = dayOf(entry, days);
            if (day <= 0) {
                warnBad(entry, "天数非法（必须在 1 到周期天数之间）");
                continue;
            }
            table.computeIfAbsent(day, k -> new ArrayList<>()).add(stack);
        }
        return table;
    }

    /** 取条目里的「天」：第一个冒号之前的整数；解析失败或越界返回 -1。 */
    private static int dayOf(String entry, int days) {
        int colon = entry.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            int day = Integer.parseInt(entry.substring(0, colon).trim());
            return day >= 1 && day <= days ? day : -1;
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            return -1;
        }
    }

    /** 解析「天:物品|数量」的一条形如 {@code 7:minecraft:iron_ingot|8} 的配置（天数由调用方再校验）。 */
    private static ItemStack parseEntry(String entry) {
        int colon = entry.indexOf(':');
        if (colon <= 0 || colon >= entry.length() - 1) {
            return ItemStack.EMPTY;
        }
        String rest = entry.substring(colon + 1).trim();
        if (rest.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 数量分隔符：优先 '|'，没有就用最后一个 ':'（这样 minecraft:diamond:1 也能读）
        int bar = rest.lastIndexOf('|');
        int sep = bar >= 0 ? bar : rest.lastIndexOf(':');
        String id = sep >= 0 ? rest.substring(0, sep).trim() : rest;
        String countText = sep >= 0 ? rest.substring(sep + 1).trim() : "";
        if (id.isEmpty()) {
            warnBad(entry, "物品 ID 为空");
            return ItemStack.EMPTY;
        }
        int count = 1;
        if (!countText.isEmpty()) {
            try {
                count = Integer.parseInt(countText);
            } catch (NumberFormatException ex) {
                warnBad(entry, "数量不是整数，按 1 处理");
                count = 1;
            }
            if (count <= 0) {
                warnBad(entry, "数量必须为正整数，按 1 处理");
                count = 1;
            }
            count = Math.min(count, 9999);
        }
        Item item = resolveItem(id);
        if (item == null) {
            warnBad(entry, "物品不存在（未注册的 ID 会被跳过，本模组不注册任何新物品）");
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, 1);
        // setCount 会自动夹到该物品的最大堆叠，配置写 9999 也不会造出异常数量
        stack.setCount(count);
        return stack;
    }

    /** 物品 ID -> Item；允许省略命名空间（补 minecraft）。解析不了返回 null。 */
    private static Item resolveItem(String id) {
        ResourceLocation rl;
        try {
            rl = id.indexOf(':') >= 0 ? ResourceLocation.parse(id) : ResourceLocation.withDefaultNamespace(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Optional<Item> found = BuiltInRegistries.ITEM.getOptional(rl);
        return found.orElse(null);
    }

    /** 坏项提醒：整张表只在<b>配置串变化时</b>重解析，所以每条坏配置每次改动只会刷一行日志。 */
    private static void warnBad(String entry, String why) {
        PlayerSuiteMod.LOGGER.warn("[checkin] 物品奖励配置项被跳过：\"{}\" —— {}", entry, why);
    }

    // ---------------------------------------------------------------- 视图

    /** 一天在界面上的快照（服务端算好下发，客户端不参与任何判定）。 */
    public record DayView(int index, long coin, int itemCount, ItemStack icon, boolean claimed,
                          boolean today, boolean past, boolean makeupable) {
    }

    /** 第 page 页要显示的天（{@link #DAYS_PER_PAGE} 天一页，末页可能不满）。 */
    public static List<DayView> pageView(CheckinData data, int page, long today) {
        int cycleDays = SuiteConfig.checkinCycleDays();
        int days = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, cycleDays));
        int from = Math.max(0, page) * DAYS_PER_PAGE;
        List<DayView> out = new ArrayList<>();
        if (from >= days) {
            return out;
        }
        int todayIndex = data.dayIndex(today, days);
        boolean allowMakeup = SuiteConfig.checkinAllowMakeup() && SuiteConfig.checkinMakeupMax() > 0;
        int makeupLeft = data.makeupLeft();
        int streak = effectiveStreak(data, today);
        for (int i = from; i < Math.min(days, from + DAYS_PER_PAGE); i++) {
            boolean claimed = data.isClaimed(i);
            boolean isToday = i == todayIndex;
            boolean past = i < todayIndex;
            long coin = coinForDay(i);
            if (isToday && !claimed) {
                // 今天签到就是第 streak+1 天，预览按下一档的连签加成算
                coin = coinWithBonus(i, Math.min(CheckinData.MAX_STREAK, Math.max(0, streak) + 1));
            }
            List<ItemStack> items = itemsForDay(i);
            int itemCount = 0;
            for (ItemStack stack : items) {
                itemCount += stack.getCount();
            }
            ItemStack icon = items.isEmpty() ? new ItemStack(Items.GOLD_INGOT, 1) : items.get(0).copyWithCount(1);
            boolean makeupable = allowMakeup && makeupLeft > 0 && past && !claimed;
            out.add(new DayView(i, coin, itemCount, icon, claimed, isToday, past, makeupable));
        }
        return out;
    }

    /** 本周期还没领的金币合计（界面「本周期剩余」与「下个周期合计」预览用）。 */
    public static long remainingCoin(CheckinData data, long today) {
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        long sum = 0L;
        for (int i = 0; i < cycleDays; i++) {
            if (!data.isClaimed(i)) {
                sum = addCapped(sum, coinForDay(i));
            }
        }
        return sum;
    }

    /** 整个周期的金币合计（下个周期奖励预览）。 */
    public static long cycleCoinTotal() {
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        long sum = 0L;
        for (int i = 0; i < cycleDays; i++) {
            sum = addCapped(sum, coinForDay(i));
        }
        return sum;
    }

    /** 加法防溢出：超过钱包上限就停在上限。 */
    private static long addCapped(long a, long b) {
        long cap = SuiteConfig.maxBalance();
        if (a >= cap || b <= 0L) {
            return Math.min(cap, Math.max(0L, a));
        }
        return Math.min(cap, a + b);
    }

    // ---------------------------------------------------------------- 打开界面

    /**
     * 打开签到页。{@code viewer} 看 {@code target}：
     * 看别人时隐藏「签到 / 补签」按钮，只显示状态（并且必须有 managePermission）。
     *
     * @param page 页码；传负数表示自动跳到「今天所在的那一页」
     */
    public static void open(ServerPlayer viewer, ServerPlayer target, int page) {
        if (viewer == null || target == null) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.CHECKIN)) {
            tell(viewer, Component.translatable("playersuite.checkin.msg.disabled"));
            return;
        }
        if (target.isRemoved()) {
            target = viewer;
        }
        boolean self = isSamePlayer(viewer, target);
        boolean manage = canManage(viewer);
        if (!self && !manage) {
            tell(viewer, Component.translatable("playersuite.msg.noPermission",
                    SuiteConfig.managePermission()));
            target = viewer;
            self = true;
        }

        long today = currentDay();
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        CheckinData data = data(target);                       // 顺带把周期滚到包含今天
        int todayIndex = data.dayIndex(today, cycleDays);
        int pages = Math.max(1, (cycleDays + DAYS_PER_PAGE - 1) / DAYS_PER_PAGE);
        int targetPage = page < 0 ? todayIndex / DAYS_PER_PAGE : page;
        int clamped = Math.max(0, Math.min(targetPage, pages - 1));
        boolean admin = canAdmin(viewer);
        // 标志位统一在服务端算好下发（客户端只能决定按钮显不显示，不能决定能不能点）
        int flags = (self ? CheckinMenu.FLAG_SELF : 0)
                | (manage ? CheckinMenu.FLAG_MANAGE : 0)
                | (admin ? CheckinMenu.FLAG_ADMIN : 0)
                | (SuiteConfig.checkinAllowMakeup() && SuiteConfig.checkinMakeupMax() > 0
                        ? CheckinMenu.FLAG_MAKEUP_ALLOWED : 0);
        final ServerPlayer shown = target;
        final int finalPage = clamped;
        final int finalPages = pages;
        viewer.openMenu(new SimpleMenuProvider(
                        (containerId, inventory, player) -> new CheckinMenu(containerId, inventory, viewer,
                                shown, finalPage, finalPages, manage, flags),
                        title(shown, self)),
                buf -> CheckinMenu.writeCheckinData(buf, viewer, shown, finalPage, finalPages, manage, flags));
    }

    public static Component title(ServerPlayer target, boolean self) {
        return self ? Component.translatable("playersuite.checkin.title")
                : Component.translatable("playersuite.checkin.title.other", target.getGameProfile().getName());
    }

    /** 刷新（签到/补签之后状态变了，容器里是打开时的快照，必须重建）。保持当前页码不跳走。 */
    public static void refresh(ServerPlayer viewer) {
        if (viewer == null) {
            return;
        }
        int page = -1;
        if (viewer.containerMenu instanceof CheckinMenu menu) {
            page = menu.page();
        }
        ServerPlayer target = managedTarget(viewer);
        open(viewer, target == null ? viewer : target, page);
    }

    /** 当前签到页指向的目标玩家；没开着签到页返回 null。 */
    public static ServerPlayer managedTarget(ServerPlayer viewer) {
        AbstractContainerMenu menu = viewer.containerMenu;
        if (menu instanceof CheckinMenu checkinMenu) {
            return checkinMenu.target();
        }
        return null;
    }

    // ---------------------------------------------------------------- 签到

    /**
     * 本人签到。
     *
     * @return 是否真的发放了奖励（false = 已签过 / 背包放不下 / 功能关闭）
     */
    public static boolean checkin(ServerPlayer player) {
        return doCheckin(player, player, false);
    }

    /** OP 代签：给 target 补上「今天」这一天，走与本人签到完全相同的校验与发放路径。 */
    public static boolean adminCheckin(ServerPlayer viewer, ServerPlayer target) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        boolean done = doCheckin(target, viewer, true);
        if (done) {
            CheckinData data = data(target);
            long today = currentDay();
            viewer.sendSystemMessage(Component.translatable("playersuite.checkin.msg.adminCheckin",
                    viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                    formatDay(today), data.dayIndex(today, SuiteConfig.checkinCycleDays()) + 1,
                    data.streak()));
        }
        return done;
    }

    /**
     * 签到核心：先判重、再判背包、<b>然后才改状态</b>、最后发钱发货。
     *
     * <p>顺序是刻意的：{@code markClaimed + save} 放在发奖之前，所以连点第二次进来时
     * 第一步就被挡回去，不会出现「一次点击领两份」；而背包检查放在改状态之前，
     * 放不下时整项物品奖励直接不发（当天保持未签），玩家整理背包后可以重签，
     * 既不会凭空消失，也不会复制。
     */
    private static boolean doCheckin(ServerPlayer target, ServerPlayer actor, boolean byAdmin) {
        if (target == null || target.isRemoved()) {
            tell(actor, Component.translatable("playersuite.checkin.msg.offline"));
            return false;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.CHECKIN)) {
            tell(actor, Component.translatable("playersuite.checkin.msg.disabled"));
            return false;
        }
        long today = currentDay();
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        CheckinData data = data(target);
        int index = data.dayIndex(today, cycleDays);
        if (data.lastDay() == today || data.isClaimed(index)) {
            tell(actor, Component.translatable("playersuite.checkin.msg.already",
                    index + 1, formatDay(today)));
            return false;
        }
        List<ItemStack> items = itemsForDay(index);
        if (!items.isEmpty() && !fitsInInventory(target, items)) {
            // 整项退回：这一天保持未领，等玩家腾出背包空间再签
            tell(actor, Component.translatable("playersuite.checkin.msg.invFull"));
            return false;
        }
        int streak = effectiveStreak(data, today);
        int nextStreak = data.lastDay() == today - 1L ? streak + 1 : 1;
        if (data.lastDay() > 0L && data.lastDay() < today - 1L) {
            nextStreak = 1;                                   // 断签：从今天重新开始数
        }
        nextStreak = Math.max(1, Math.min(CheckinData.MAX_STREAK, nextStreak));

        long bonus = streakBonus(nextStreak);
        long coin = coinWithBonus(index, nextStreak);

        data.setLastDay(today);
        data.setStreak(nextStreak);
        data.markClaimed(index);
        save(target, data);                                   // 状态先落地，再发奖（幂等）

        pay(target, coin, items, REASON_CHECKIN);
        Component rewardLine = Component.translatable("playersuite.checkin.line.reward",
                Economy.formatString(coinForDay(index)), Economy.formatString(bonus), itemSummary(items));
        tell(target, Component.translatable("playersuite.checkin.msg.checked",
                index + 1, formatDay(today), nextStreak, rewardLine));
        if (byAdmin && !isSamePlayer(actor, target)) {
            tell(actor, Component.translatable("playersuite.checkin.msg.adminDone",
                    target.getGameProfile().getName()));
            tell(target, Component.translatable("playersuite.checkin.msg.adminHelped",
                    actor.getGameProfile().getName()));
        }
        target.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.3F);
        return true;
    }

    // ---------------------------------------------------------------- 补签

    /**
     * 补签本周期内<b>已经错过</b>的一天（不能提前签未来的天）。
     *
     * <p>先扣钱成功再改状态发奖；补签只补标记与当天基础奖励，<b>不发连签加成、不改 streak</b>
     * （否则花钱就能把连签奖励刷满）。
     */
    public static boolean makeup(ServerPlayer player, int index) {
        if (!enabled(player)) {
            return false;
        }
        if (!SuiteConfig.checkinAllowMakeup()) {
            tell(player, Component.translatable("playersuite.checkin.msg.makeupOff"));
            return false;
        }
        long today = currentDay();
        int cycleDays = Math.max(1, Math.min(CheckinData.MAX_CYCLE_DAYS, SuiteConfig.checkinCycleDays()));
        CheckinData data = data(player);
        int todayIndex = data.dayIndex(today, cycleDays);
        if (index < 0 || index >= cycleDays) {
            tell(player, Component.translatable("playersuite.checkin.msg.badDay", index + 1, cycleDays));
            return false;
        }
        if (index >= todayIndex) {
            tell(player, Component.translatable("playersuite.checkin.msg.futureDay", index + 1));
            return false;
        }
        if (data.isClaimed(index)) {
            tell(player, Component.translatable("playersuite.checkin.msg.already",
                    index + 1, formatDay(data.cycleStartDay() + index)));
            return false;
        }
        if (data.makeupLeft() <= 0) {
            tell(player, Component.translatable("playersuite.checkin.msg.makeupUsedUp",
                    SuiteConfig.checkinMakeupMax()));
            return false;
        }
        List<ItemStack> items = itemsForDay(index);
        if (!items.isEmpty() && !fitsInInventory(player, items)) {
            tell(player, Component.translatable("playersuite.checkin.msg.invFull"));
            return false;
        }
        long cost = Math.max(0L, SuiteConfig.checkinMakeupCost());
        if (cost > 0L && !Economy.withdraw(player, cost, REASON_MAKEUP)) {
            tell(player, Component.translatable("playersuite.checkin.msg.shortage",
                    Economy.label(cost), Economy.label(Economy.balance(player))));
            return false;
        }
        data.markClaimed(index);
        data.setMakeupUsed(data.makeupUsed() + 1);
        save(player, data);                                   // 扣钱成功之后才改状态

        long coin = coinForDay(index);
        pay(player, coin, items, REASON_CHECKIN);
        tell(player, Component.translatable("playersuite.checkin.msg.madeup",
                index + 1, Economy.label(cost), data.makeupLeft(),
                Component.translatable("playersuite.checkin.line.reward",
                        Economy.formatString(coin), Economy.formatString(0L), itemSummary(items))));
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.0F);
        return true;
    }

    // ---------------------------------------------------------------- 发奖

    /** 金币走 Economy.deposit，物品走背包；模拟放不下的情况在这里不会再出现，兜底掉在脚下。 */
    private static void pay(ServerPlayer player, long coin, List<ItemStack> items, String reason) {
        if (coin > 0L) {
            Economy.deposit(player, coin, reason);
        }
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack copy = stack.copy();
            player.getInventory().add(copy);
            if (!copy.isEmpty()) {
                // 理论上进来之前已经整批模拟过，不会有余量；真出现就掉在脚下，绝不吞物品
                player.drop(copy, false);
            }
        }
    }

    /** 背包能否装下全部这些堆栈（在副本上模拟原版放物规则，不动真实背包）。 */
    static boolean fitsInInventory(Player player, List<ItemStack> stacks) {
        List<ItemStack> sim = new ArrayList<>(player.getInventory().items.size());
        for (ItemStack existing : player.getInventory().items) {
            sim.add(existing.copy());
        }
        for (ItemStack stack : stacks) {
            if (!placeSimulated(sim, stack.copy())) {
                return false;
            }
        }
        return true;
    }

    private static boolean placeSimulated(List<ItemStack> sim, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (stack.isDamaged()) {
            for (int i = 0; i < sim.size(); i++) {
                if (sim.get(i).isEmpty()) {
                    sim.set(i, stack);
                    return true;
                }
            }
            return false;
        }
        if (stack.isStackable()) {
            for (int i = 0; i < sim.size() && !stack.isEmpty(); i++) {
                ItemStack target = sim.get(i);
                if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) {
                    continue;
                }
                int space = target.getMaxStackSize() - target.getCount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, stack.getCount());
                target.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < sim.size() && !stack.isEmpty(); i++) {
            if (sim.get(i).isEmpty()) {
                int put = Math.min(stack.getMaxStackSize(), stack.getCount());
                sim.set(i, stack.split(put));
            }
        }
        return stack.isEmpty();
    }

    /**
     * 物品奖励的可读小结，例如 {@code 钻石x1}、{@code 铁锭x8 + 金粒x16}。
     *
     * <p>这里<b>不用 {@code getString()}</b>：服务端没有语言文件，把翻译键转成字符串会变成
     * {@code item.minecraft.diamond}；直接返回 {@link Component}，本地化交给客户端。
     */
    public static Component itemSummary(List<ItemStack> items) {
        MutableComponent out = Component.literal("");
        if (items == null || items.isEmpty()) {
            return out;
        }
        boolean first = true;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                out.append(" + ");
            }
            first = false;
            out.append(stack.getHoverName());
            out.append(Component.literal("x" + stack.getCount()));
        }
        if (first) {
            return Component.literal("");
        }
        return Component.literal(" + ").append(out);   // 接在「金币 …（含连签加成 …）」后面
    }

    // ---------------------------------------------------------------- OP 管理

    /** 能否查看他人签到页（默认 OP 2）。 */
    public static boolean canManage(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.managePermission());
    }

    /** 能否代签 / 重置 / 强制设定（默认 OP 3）。 */
    public static boolean canAdmin(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.adminPermission());
    }

    public static boolean isSamePlayer(Player a, Player b) {
        return a != null && b != null && a.getUUID().equals(b.getUUID());
    }

    /** 重置指定玩家的连续天数与补签次数（<b>故意不清 claimedMask</b>：清了就能把本周期奖励重领一遍）。 */
    public static boolean adminReset(ServerPlayer viewer, ServerPlayer target) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        CheckinData data = data(target);
        int beforeStreak = data.streak();
        int beforeMakeup = data.makeupUsed();
        data.setStreak(0);
        data.setMakeupUsed(0);
        save(target, data);
        viewer.sendSystemMessage(Component.translatable("playersuite.checkin.msg.adminReset",
                viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                beforeStreak, data.streak(), beforeMakeup, data.makeupUsed()));
        tell(target, Component.translatable("playersuite.checkin.msg.adminNotified",
                viewer.getGameProfile().getName()));
        return true;
    }

    /**
     * 强制设定连续天数。为了让设定值真正生效（否则下次签到会按「上次签到日是不是昨天」重算），
     * 这里同时把 {@code lastDay} 设成「昨天」，表示「连签状态接着往后数」。
     */
    public static boolean adminSetStreak(ServerPlayer viewer, ServerPlayer target, int value) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        int applied = Math.max(0, Math.min(CheckinData.MAX_STREAK, value));
        CheckinData data = data(target);
        int before = data.streak();
        long today = currentDay();
        data.setStreak(applied);
        if (!data.isClaimed(data.dayIndex(today, SuiteConfig.checkinCycleDays()))) {
            data.setLastDay(today - 1L);
        }
        save(target, data);
        viewer.sendSystemMessage(Component.translatable("playersuite.checkin.msg.adminStreak",
                viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                before, applied));
        tell(target, Component.translatable("playersuite.checkin.msg.adminNotified",
                viewer.getGameProfile().getName()));
        return true;
    }

    /** 管理动作的二次校验：功能开启 + adminPermission + 目标在线（看别人 = manage，动别人 = admin）。 */
    private static boolean checkAdmin(ServerPlayer viewer, ServerPlayer target) {
        if (!SuiteConfig.featureEnabled(FeatureKeys.CHECKIN)) {
            tell(viewer, Component.translatable("playersuite.checkin.msg.disabled"));
            return false;
        }
        if (!canAdmin(viewer)) {
            tell(viewer, Component.translatable("playersuite.msg.noPermission",
                    SuiteConfig.adminPermission()));
            return false;
        }
        if (target == null || target.isRemoved()) {
            tell(viewer, Component.translatable("playersuite.checkin.msg.offline"));
            return false;
        }
        return true;
    }

    private static boolean enabled(ServerPlayer player) {
        if (SuiteConfig.featureEnabled(FeatureKeys.CHECKIN)) {
            return true;
        }
        tell(player, Component.translatable("playersuite.checkin.msg.disabled"));
        return false;
    }

    // ---------------------------------------------------------------- 文本输入

    /** 查看他人：输入玩家名（只在离线玩家在线时可用，本模组不碰离线档）。 */
    public static ServerPlayer findOnline(ServerPlayer viewer, String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            return null;
        }
        return viewer.server.getPlayerList().getPlayerByName(trimmed);
    }

    /**
     * 签到页的打字入口（由 {@code network/InputRouter} 接线）。
     *
     * <p>客户端输入完全不可信：权限、目标、数值全部在这里再判一次。
     */
    public static void onTextInput(ServerPlayer player, String action, String text) {
        if (player == null || action == null) {
            return;
        }
        if (!enabled(player)) {
            return;
        }
        String trimmed = text == null ? "" : text.trim();
        switch (action) {
            case ACTION_TARGET -> {
                if (!canManage(player)) {
                    tell(player, Component.translatable("playersuite.msg.noPermission",
                            SuiteConfig.managePermission()));
                    return;
                }
                ServerPlayer target = findOnline(player, trimmed);
                if (target == null) {
                    tell(player, Component.translatable("playersuite.checkin.msg.offlineNamed", trimmed));
                    return;
                }
                open(player, target, -1);
            }
            case ACTION_ADMIN_STREAK -> {
                ServerPlayer target = managedTarget(player);
                if (target == null || isSamePlayer(player, target)) {
                    tell(player, Component.translatable("playersuite.checkin.msg.needTarget"));
                    return;
                }
                if (!canAdmin(player)) {
                    tell(player, Component.translatable("playersuite.msg.noPermission",
                            SuiteConfig.adminPermission()));
                    return;
                }
                int value = parseInt(trimmed);
                if (value < 0) {
                    tell(player, Component.translatable("playersuite.checkin.msg.parseFail", trimmed));
                    return;
                }
                if (adminSetStreak(player, target, value)) {
                    refresh(player);
                }
            }
            default -> tell(player, Component.translatable("playersuite.checkin.msg.unknownAction", action));
        }
    }

    /** 纯数字解析（拒绝负号、空格、越界）；失败返回 -1。 */
    private static int parseInt(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 6) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            if (value > CheckinData.MAX_STREAK) {
                return -1;
            }
        }
        return value;
    }

    // ---------------------------------------------------------------- 杂项

    private static void tell(ServerPlayer player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    /** 可点击的「打开签到页」文本，进服提示用（照 {@code BankService.openLink()} 的写法）。 */
    public static Component openLink() {
        return Component.translatable("playersuite.checkin.link")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/" + SuiteConfig.hubRoot() + " " + FeatureKeys.CHECKIN)));
    }

}
