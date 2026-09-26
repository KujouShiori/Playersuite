package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.mcmods.playersuite.ui.SuiteMenuProvider;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * 银行的业务逻辑：存取款、取款手续费与冷却、每日利息结算、流水、OP 管理。
 *
 * <p>所有方法都只在逻辑服务端调用；界面上的按钮只发编号，金额与权限在这里二次校验。
 *
 * <p><b>溢出约定</b>：所有百分比都用「万分比 bps（long）」表达，并且一律<b>先整除再相乘</b>：
 * {@code value = (amount / 10000) * bps + ((amount % 10000) * bps) / 10000}，
 * 余数部分最大 9999 * 10000 = 1e8，永远不会把 long 乘爆；取整方向也和直接乘除完全一致。
 */
public final class BankService {
    /** 一个结息周期：满 24 小时才结一次（不是每自然日）。 */
    public static final long PERIOD_MS = 24L * 60L * 60L * 1000L;
    /** 百分比统一放大到万分比，避免在金额上直接乘浮点。 */
    public static final long PERCENT_BASE = 10_000L;
    /** 快捷按钮最多几个（与 {@code BankMenu.QUICK_SLOTS} 一致）。 */
    public static final int MAX_QUICK = 5;
    /** quickAmounts 解析不出任何正数时使用的默认值。 */
    public static final long[] DEFAULT_QUICK = {10L, 50L, 100L, 500L, 1000L};

    // ---- 文本输入动作名（InputRouter 接线后由 onTextInput 分发） ----
    public static final String ACTION_DEPOSIT = "deposit";
    public static final String ACTION_WITHDRAW = "withdraw";
    public static final String ACTION_ADMIN_SET = "admin_set";
    public static final String ACTION_ADMIN_OPEN = "admin_open";

    // ---- 流水原因短键（写入 BankData，同时作为 playersuite.bank.history.* 的后缀） ----
    public static final String REASON_DEPOSIT = "deposit";
    public static final String REASON_WITHDRAW = "withdraw";
    public static final String REASON_FEE = "fee";
    public static final String REASON_INTEREST = "interest";
    public static final String REASON_ADMIN_SET = "admin_set";
    public static final String REASON_ADMIN_CLEAR = "admin_clear";
    public static final String REASON_FREEZE = "freeze";
    public static final String REASON_UNFREEZE = "unfreeze";

    private BankService() {
    }

    // ---------------------------------------------------------------- 数据访问

    /** 读账户并做一次自愈（流水裁剪、负数归零）。 */
    public static BankData data(Player player) {
        BankData data = player.getData(BankAttachments.BANK);
        data.validate();
        return data;
    }

    /** 附件是可变对象，改完必须 setData 触发脏标记，否则不落盘。 */
    public static void save(Player player, BankData data) {
        player.setData(BankAttachments.BANK, data);
    }

    /** 功能是否启用（被关闭时所有入口直接提示，不碰数据）。 */
    private static boolean enabled(ServerPlayer player) {
        if (SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            return true;
        }
        player.displayClientMessage(Component.translatable("playersuite.bank.msg.disabled"), false);
        return false;
    }

    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    // ---------------------------------------------------------------- 配置换算

    /** 快捷金额（过滤掉非正数；一个都不剩时用 {@link #DEFAULT_QUICK}），最多 {@link #MAX_QUICK} 个。 */
    public static long[] quickAmounts() {
        long[] raw = SuiteConfig.bankQuickAmounts();
        List<Long> kept = new ArrayList<>(MAX_QUICK);
        if (raw != null) {
            for (long value : raw) {
                if (value > 0L) {
                    kept.add(value);
                    if (kept.size() >= MAX_QUICK) {
                        break;
                    }
                }
            }
        }
        if (kept.isEmpty()) {
            return DEFAULT_QUICK.clone();
        }
        long[] out = new long[kept.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kept.get(i);
        }
        return out;
    }

    /** 日利率的万分比（1.0% -> 100），向下取整到整数 bps，避免界面出现半分钱。 */
    public static long rateBps() {
        return clampBps((long) Math.floor(SuiteConfig.bankDailyInterestPercent() * 100.0D));
    }

    /** 取款手续费的万分比（0.5% -> 50）。 */
    public static long feeBps() {
        return clampBps((long) Math.floor(SuiteConfig.bankWithdrawFeePercent() * 100.0D));
    }

    private static long clampBps(long bps) {
        if (bps < 0L) {
            return 0L;
        }
        return Math.min(bps, 100L * PERCENT_BASE);   // 最多 100%
    }

    /**
     * {@code amount * bps / 10000} 向下取整，<b>先除后乘</b>防溢出。
     *
     * <p>拆成 {@code (amount/10000)*bps} 与 {@code (amount%10000)*bps/10000} 两段，
     * 结果与直接乘除一致，但余数段最大只有 9999*1e6 量级，不会溢出 long。
     */
    public static long percentFloor(long amount, long bps) {
        if (amount <= 0L || bps <= 0L) {
            return 0L;
        }
        long base = amount / PERCENT_BASE;
        long rest = amount % PERCENT_BASE;
        if (bps > 1L && base > Long.MAX_VALUE / bps) {
            return Long.MAX_VALUE;                       // 极端配置下的溢出兜底
        }
        return base * bps + (rest * bps) / PERCENT_BASE;
    }

    /** {@code amount * bps / 10000} 向上取整（手续费要求向上取整，玩家不会少扣一分）。 */
    public static long percentCeil(long amount, long bps) {
        if (amount <= 0L || bps <= 0L) {
            return 0L;
        }
        long base = amount / PERCENT_BASE;
        long rest = amount % PERCENT_BASE;
        long restValue = rest * bps;                       // < 1e4 * 1e6，安全
        long restCeil = (restValue + PERCENT_BASE - 1) / PERCENT_BASE;
        if (bps > 1L && base > Long.MAX_VALUE / bps) {
            return amount;                                // 溢出时直接按全额收（配置事故）
        }
        return Math.max(0L, base * bps + restCeil);
    }

    /** 本次取款的手续费（已按取款额封顶）。 */
    public static long feeOf(long amount) {
        long bps = feeBps();
        if (amount <= 0L || bps <= 0L) {
            return 0L;
        }
        return Math.min(amount, percentCeil(amount, bps));
    }

    // ---------------------------------------------------------------- 打开界面

    /** 打开主页（{@code page} 只作占位，主页不分页）。 */
    public static void open(ServerPlayer viewer, ServerPlayer target, int page) {
        openMode(viewer, target, 0, BankMenu.MODE_MAIN);
    }

    /** 打开流水页第 {@code page} 页。 */
    public static void openHistory(ServerPlayer viewer, ServerPlayer target, int page) {
        openMode(viewer, target, page, BankMenu.MODE_HISTORY);
    }

    /**
     * 真正的打开入口：先结算利息（幂等），再按模式组容器附加数据。
     *
     * @param mode {@link BankMenu#MODE_MAIN} 或 {@link BankMenu#MODE_HISTORY}
     */
    public static void openMode(ServerPlayer viewer, ServerPlayer target, int page, int mode) {
        if (viewer == null || target == null) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            viewer.displayClientMessage(Component.translatable("playersuite.bank.msg.disabled"), false);
            return;
        }
        if (target.isRemoved()) {
            target = viewer;
        }
        // 「打开页面」是三个结算时机之一；必须在读数据之前结算，界面上才是最新本金。
        settleInterest(target);

        boolean self = isSamePlayer(viewer, target);
        boolean manage = canManage(viewer);
        boolean admin = canAdmin(viewer);
        // 没有管理权限的人不能借 /ps bank <玩家> 看别人的账户，一律退回自己。
        if (!self && !manage) {
            tell(viewer, Component.translatable("playersuite.bank.msg.noManage"));
            target = viewer;
            self = true;
        }

        BankData data = data(target);
        int historyShown = Math.min(SuiteConfig.bankHistorySize(), data.history().size());
        int pages = mode == BankMenu.MODE_HISTORY
                ? Math.max(1, (historyShown + BankMenu.HISTORY_PER_PAGE - 1) / BankMenu.HISTORY_PER_PAGE)
                : 1;
        int clamped = Math.max(0, Math.min(page, pages - 1));

        final ServerPlayer shown = target;
        final int finalMode = mode;
        final int finalPage = clamped;
        final int historyTotal = historyShown;
        final boolean finalSelf = self;
        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, player) -> new BankMenu(containerId, inventory, viewer, shown,
                        finalMode, finalPage, pages, manage, admin, finalSelf, historyTotal),
                title(shown, viewer, finalMode));
        viewer.openMenu(provider, buf -> BankMenu.writeBankData(buf, viewer, shown, finalMode, finalPage,
                pages, manage, admin, finalSelf, historyTotal));
    }

    public static Component title(ServerPlayer target, ServerPlayer viewer, int mode) {
        if (mode == BankMenu.MODE_HISTORY) {
            return Component.translatable("playersuite.bank.title.history");
        }
        if (isSamePlayer(viewer, target)) {
            return Component.translatable("playersuite.bank.title");
        }
        return Component.translatable("playersuite.bank.title.other", target.getGameProfile().getName());
    }

    // ---------------------------------------------------------------- 权限

    /** 能否查看/管理他人账户（默认 OP 2）。 */
    public static boolean canManage(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.managePermission());
    }

    /** 能否执行破坏性管理操作（默认 OP 3）。 */
    public static boolean canAdmin(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.adminPermission());
    }

    public static boolean isSamePlayer(Player a, Player b) {
        return a != null && b != null && a.getUUID().equals(b.getUUID());
    }

    // ---------------------------------------------------------------- 存款

    /**
     * 存钱：扣钱包 -> 加本金 -> 写流水。
     *
     * @return 是否成功
     */
    public static boolean deposit(ServerPlayer player, long amount) {
        if (!enabled(player)) {
            return false;
        }
        if (amount <= 0L) {
            tell(player, Component.translatable("playersuite.bank.msg.invalid"));
            return false;
        }
        BankData data = data(player);
        if (data.frozen()) {
            tell(player, Component.translatable("playersuite.bank.msg.frozen"));
            return false;
        }
        long min = SuiteConfig.bankMinDeposit();
        if (amount < min) {
            tell(player, Component.translatable("playersuite.bank.msg.belowMin", Economy.label(min)));
            return false;
        }
        long headroom = data.headroom();
        if (amount > headroom) {
            tell(player, Component.translatable("playersuite.bank.msg.overMax",
                    Economy.label(SuiteConfig.bankMaxPrincipal()), Economy.label(headroom)));
            return false;
        }
        if (Economy.balance(player) < amount) {
            tell(player, Component.translatable("playersuite.bank.msg.shortage",
                    Economy.label(amount), Economy.label(Economy.balance(player))));
            return false;
        }
        if (!Economy.withdraw(player, amount, "bank_deposit")) {
            tell(player, Component.translatable("playersuite.bank.msg.shortage",
                    Economy.label(amount), Economy.label(Economy.balance(player))));
            return false;
        }
        // 第一次存钱时才开始计息，避免新号一上线就被算走一个「0 本金」周期。
        if (data.lastInterestAt() <= 0L) {
            data.setLastInterestAt(Util.getMillis());
        }
        data.addPrincipal(amount);
        data.addHistory(Util.getMillis(), REASON_DEPOSIT, amount);
        save(player, data);
        tell(player, Component.translatable("playersuite.bank.msg.deposited",
                Economy.label(amount), Economy.label(data.principal())));
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.4F);
        return true;
    }

    // ---------------------------------------------------------------- 取款

    /**
     * 取钱：校验冷却 -> 扣本金 -> 手续费销毁 -> 税后金额进钱包 -> 写流水。
     *
     * @return 是否成功
     */
    public static boolean withdraw(ServerPlayer player, long amount) {
        if (!enabled(player)) {
            return false;
        }
        if (amount <= 0L) {
            tell(player, Component.translatable("playersuite.bank.msg.invalid"));
            return false;
        }
        BankData data = data(player);
        if (data.frozen()) {
            tell(player, Component.translatable("playersuite.bank.msg.frozen"));
            return false;
        }
        int cooldownSeconds = SuiteConfig.bankWithdrawCooldownSeconds();
        long now = Util.getMillis();
        if (cooldownSeconds > 0 && data.lastWithdrawAt() > 0L) {
            // 冷却用时间戳算，不靠界面倒计时（客户端说什么都不算，这里必须二次校验）。
            long waitMs = data.lastWithdrawAt() + cooldownSeconds * 1000L;
            if (now < waitMs) {
                tell(player, Component.translatable("playersuite.bank.msg.cooldown",
                        (waitMs - now + 999L) / 1000L));
                return false;
            }
        }
        if (data.principal() < amount) {
            tell(player, Component.translatable("playersuite.bank.msg.overPrincipal",
                    Economy.label(data.principal())));
            return false;
        }
        if (!data.takePrincipal(amount)) {
            tell(player, Component.translatable("playersuite.bank.msg.overPrincipal",
                    Economy.label(data.principal())));
            return false;
        }
        long fee = feeOf(amount);
        long net = amount - fee;                           // 税后金额，先算再进钱包
        data.setLastWithdrawAt(now);
        data.addHistory(now, REASON_WITHDRAW, -amount);
        if (fee > 0L) {
            // 手续费直接销毁：不给任何玩家，也不进任何池子，只留一条流水。
            data.addHistory(now, REASON_FEE, -fee);
        }
        save(player, data);
        if (net > 0L) {
            Economy.deposit(player, net, "bank_withdraw");
        }
        tell(player, fee > 0L
                ? Component.translatable("playersuite.bank.msg.withdrawnFee",
                        Economy.label(amount), Economy.label(fee), Economy.label(net),
                        Economy.label(data.principal()))
                : Component.translatable("playersuite.bank.msg.withdrawn",
                        Economy.label(amount), Economy.label(net), Economy.label(data.principal())));
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.0F);
        return true;
    }

    // ---------------------------------------------------------------- 利息

    /**
     * 结算利息：<b>满 24 小时</b>一个周期，可一次补多个周期；单周期利息封顶
     * {@code bankInterestMaxPerDay()}。
     *
     * <p>幂等写法：先把 {@code lastInterestAt} 推进（并落盘）再发钱，
     * 因此哪怕 {@code Economy.deposit} 之前被打断，也绝不会重复发息。
     *
     * @return 实际发放的利息（0 表示这次没结算）
     */
    public static long settleInterest(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return 0L;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            return 0L;
        }
        BankData data = data(player);
        long now = Util.getMillis();
        long last = data.lastInterestAt();
        if (last <= 0L) {
            // 老存档或全新玩家：先给一个起点，不发钱。
            data.setLastInterestAt(now);
            save(player, data);
            return 0L;
        }
        if (now - last < PERIOD_MS) {
            return 0L;
        }
        long periods = (now - last) / PERIOD_MS;
        // 先推进时间戳（只推进整数个周期，剩下的零头留给下一次）。
        long advanced = last + periods * PERIOD_MS;
        data.setLastInterestAt(advanced > 0L ? advanced : now);
        save(player, data);

        long bps = rateBps();
        long principal = data.principal();
        if (bps <= 0L || principal <= 0L) {
            return 0L;
        }
        long perPeriod = percentFloor(principal, bps);
        long cap = SuiteConfig.bankInterestMaxPerDay();
        if (cap > 0L) {
            perPeriod = Math.min(perPeriod, cap);
        }
        if (perPeriod <= 0L) {
            return 0L;
        }
        // 多周期合计也要防溢出：超过 long 能表达的范围就按余额上限发。
        long total = perPeriod > Long.MAX_VALUE / periods ? SuiteConfig.maxBalance() : perPeriod * periods;
        total = Math.min(total, SuiteConfig.maxBalance());
        Economy.deposit(player, total, "bank_interest");
        data.addHistory(now, REASON_INTEREST, total);
        save(player, data);
        tell(player, Component.translatable("playersuite.bank.msg.interest",
                Economy.label(total), periods, Economy.label(data.principal())));
        return total;
    }

    /** 服务器定时任务：结算所有在线玩家（每 60 秒一次，见 {@link BankEvents}）。 */
    public static void settleAll(MinecraftServer server) {
        if (server == null || !SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            settleInterest(players.get(i));
        }
    }

    /** 距下次结息的剩余毫秒（0 表示已经到期）。 */
    public static long nextInterestDelay(ServerPlayer player) {
        BankData data = data(player);
        long last = data.lastInterestAt();
        if (last <= 0L || rateBps() <= 0L) {
            return -1L;                                  // -1 = 不计时（未开始或利息关闭）
        }
        return Math.max(0L, last + PERIOD_MS - Util.getMillis());
    }

    /** 距可再次取款的剩余毫秒（0 表示可以随时取）。 */
    public static long withdrawDelay(ServerPlayer player) {
        BankData data = data(player);
        int seconds = SuiteConfig.bankWithdrawCooldownSeconds();
        if (seconds <= 0 || data.lastWithdrawAt() <= 0L) {
            return 0L;
        }
        return Math.max(0L, data.lastWithdrawAt() + seconds * 1000L - Util.getMillis());
    }

    // ---------------------------------------------------------------- OP 管理

    /** 找到某个在线玩家（管理他人时用），找不到返回 null。 */
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

    /** 当前打开着的银行容器所指向的目标玩家；没开着银行页返回 null。 */
    public static ServerPlayer managedTarget(ServerPlayer viewer) {
        AbstractContainerMenu menu = viewer.containerMenu;
        if (menu instanceof BankMenu bankMenu) {
            return bankMenu.target();
        }
        return null;
    }

    /** 管理员设定他人本金（不经过钱包，直接改本金，并记一条流水）。 */
    public static boolean adminSetPrincipal(ServerPlayer viewer, ServerPlayer target, long value) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        long cap = SuiteConfig.bankMaxPrincipal();
        long applied = Math.max(0L, Math.min(value, cap));
        BankData data = data(target);
        long before = data.principal();
        long delta = applied - before;                     // 可能为负，仅作流水展示
        data.setPrincipal(applied);
        if (data.lastInterestAt() <= 0L && applied > 0L) {
            data.setLastInterestAt(Util.getMillis());
        }
        if (delta != 0L) {
            data.addHistory(Util.getMillis(), REASON_ADMIN_SET, delta);
        }
        save(target, data);
        logAdmin(viewer, target, "playersuite.bank.msg.adminSet", applied, before);
        tell(target, Component.translatable("playersuite.bank.msg.notifiedSet",
                viewer.getGameProfile().getName(), Economy.label(applied)));
        return true;
    }

    /** 管理员清零他人本金（不动钱包，只把本金归零）。 */
    public static boolean adminClearPrincipal(ServerPlayer viewer, ServerPlayer target) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        BankData data = data(target);
        long before = data.principal();
        if (before <= 0L) {
            tell(viewer, Component.translatable("playersuite.bank.msg.alreadyEmpty",
                    target.getGameProfile().getName()));
            return false;
        }
        data.setPrincipal(0L);
        data.addHistory(Util.getMillis(), REASON_ADMIN_CLEAR, -before);
        save(target, data);
        logAdmin(viewer, target, "playersuite.bank.msg.adminClear", 0L, before);
        tell(target, Component.translatable("playersuite.bank.msg.notifiedClear",
                viewer.getGameProfile().getName(), Economy.label(before)));
        return true;
    }

    /** 管理员冻结/解冻账户（冻结后该玩家无法存取）。 */
    public static boolean adminToggleFreeze(ServerPlayer viewer, ServerPlayer target) {
        if (!checkAdmin(viewer, target)) {
            return false;
        }
        BankData data = data(target);
        boolean frozen = !data.frozen();
        data.setFrozen(frozen);
        data.addHistory(Util.getMillis(), frozen ? REASON_FREEZE : REASON_UNFREEZE, 0L);
        save(target, data);
        logAdmin(viewer, target, frozen ? "playersuite.bank.msg.adminFrozen"
                : "playersuite.bank.msg.adminUnfrozen", data.principal(), data.principal());
        if (frozen) {
            tell(target, Component.translatable("playersuite.bank.msg.notifiedFrozen",
                    viewer.getGameProfile().getName()));
        } else {
            tell(target, Component.translatable("playersuite.bank.msg.notifiedUnfrozen",
                    viewer.getGameProfile().getName()));
        }
        return true;
    }

    /** 管理动作的二次校验：权限 + 目标在线 + 不能拿自己开刀（清零/冻结自己走命令更合适）。 */
    private static boolean checkAdmin(ServerPlayer viewer, ServerPlayer target) {
        if (!SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            tell(viewer, Component.translatable("playersuite.bank.msg.disabled"));
            return false;
        }
        if (!canAdmin(viewer)) {
            tell(viewer, Component.translatable("playersuite.bank.msg.noAdmin",
                    SuiteConfig.adminPermission()));
            return false;
        }
        if (target == null || target.isRemoved()) {
            tell(viewer, Component.translatable("playersuite.bank.msg.offline",
                    target == null ? "?" : target.getGameProfile().getName()));
            return false;
        }
        return true;
    }

    /** 三个管理动作都要在服务端记一条系统消息：操作者 / 目标 / 数值。 */
    private static void logAdmin(ServerPlayer viewer, ServerPlayer target, String key, long after, long before) {
        viewer.sendSystemMessage(Component.translatable(key,
                viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                Economy.label(after), Economy.label(before)));
    }

    // ---------------------------------------------------------------- 文本输入

    /**
     * 银行页所有打字入口的统一分发（由 {@code network/InputRouter} 接线）。
     *
     * <p>客户端输入完全不可信：长度、数字、权限、冷却、上限全部在这里重新校验一遍。
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
            case ACTION_DEPOSIT -> {
                long amount = parseAmount(trimmed);
                if (amount <= 0L) {
                    tell(player, Component.translatable("playersuite.bank.msg.parseFail", trimmed));
                    return;
                }
                if (deposit(player, amount)) {
                    open(player, player, 0);
                }
            }
            case ACTION_WITHDRAW -> {
                long amount = parseAmount(trimmed);
                if (amount <= 0L) {
                    tell(player, Component.translatable("playersuite.bank.msg.parseFail", trimmed));
                    return;
                }
                if (withdraw(player, amount)) {
                    open(player, player, 0);
                }
            }
            case ACTION_ADMIN_OPEN -> {
                if (!canManage(player)) {
                    tell(player, Component.translatable("playersuite.bank.msg.noManage"));
                    return;
                }
                ServerPlayer target = findOnline(player, trimmed);
                if (target == null) {
                    tell(player, Component.translatable("playersuite.bank.msg.offline", trimmed));
                    return;
                }
                open(player, target, 0);
            }
            case ACTION_ADMIN_SET -> {
                ServerPlayer target = managedTarget(player);
                if (target == null || isSamePlayer(player, target)) {
                    tell(player, Component.translatable("playersuite.bank.msg.needTarget"));
                    return;
                }
                long value = parseAmount(trimmed);
                if (value < 0L) {
                    tell(player, Component.translatable("playersuite.bank.msg.parseFail", trimmed));
                    return;
                }
                if (adminSetPrincipal(player, target, value)) {
                    open(player, target, 0);
                }
            }
            default -> tell(player, Component.translatable("playersuite.bank.msg.unknownAction", action));
        }
    }

    /** 纯数字解析（拒绝负号、空格、超出 long 的输入）；失败返回 -1。 */
    private static long parseAmount(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 19) {
            return -1L;
        }
        long value = 0L;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
            long next = value * 10L + (c - '0');
            if (next < value) {
                return -1L;                              // 溢出
            }
            value = next;
        }
        return value;
    }

    // ---------------------------------------------------------------- 展示辅助

    /** 可点击的「打开银行」文本，用于进服提示。 */
    public static Component openLink() {
        return Component.translatable("playersuite.bank.link")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/" + SuiteConfig.hubRoot() + " " + FeatureKeys.BANK)));
    }

    /** 功能总入口用的打开方法（{@code FeatureOpeners.open} 接线后调用）。 */
    public static void openFeature(ServerPlayer player, int page) {
        open(player, player, page);
    }

    /** 银行页标题右侧的简短说明：当前本金。 */
    public static Component principalLabel(long principal) {
        return Economy.label(Math.max(0L, principal));
    }

    /** 动作生效成功后刷新界面（余额/本金都变了，容器里的值是打开时的快照）。 */
    public static void refresh(ServerPlayer viewer) {
        if (viewer.containerMenu instanceof BankMenu menu) {
            openMode(viewer, menu.target() == null ? viewer : menu.target(), menu.page(), menu.mode());
            return;
        }
        open(viewer, viewer, 0);
    }

    /** 供其它功能查询：某人是否被冻结（不含任何副作用）。 */
    public static boolean isFrozen(Player player) {
        return player.getData(BankAttachments.BANK).frozen();
    }
}
