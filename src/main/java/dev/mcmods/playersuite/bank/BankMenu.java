package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 银行容器：一个菜单两种模式。
 *
 * <pre>
 *   MODE_MAIN    存取款主页：本金/利率/结息倒计时 + 快捷存取 + 自定义金额 + 入口按钮
 *   MODE_HISTORY 流水页：每页 {@link #HISTORY_PER_PAGE} 条（时间/说明/金额/余额）
 * </pre>
 *
 * <p>界面几何常量全部放在这里，客户端 {@code BankScreen} 直接引用，保证服务端与客户端不错位。
 *
 * <p>附加数据（openMenu 的 buf）顺序见 {@link #writeBankData}，客户端构造函数必须逐字段对齐读取。
 * 之所以不用 {@code writeFull} 的纯 int 数组，是因为本金/快捷金额是 long，
 * 流水还要带原因字符串；这里复用 {@code writeHeader} 公共头，后面接自己的字段。
 */
public class BankMenu extends PageMenu {
    public static final int MODE_MAIN = 0;
    public static final int MODE_HISTORY = 1;

    // ---- 界面几何（面板相对坐标，与 PageScreen 的行布局一致）----
    /** 内容区行数：基类据此算底部按钮行与面板高度。 */
    public static final int CONTENT_ROWS = 8;
    /** 内容区左右边距（与 {@code Layout.GRID_LEFT} 同值，本功能自己引用，不动共享文件）。 */
    public static final int LEFT = 8;
    public static final int RIGHT = 168;
    public static final int INFO_Y1 = 21;
    public static final int INFO_Y2 = 31;
    public static final int INFO_Y3 = 41;
    public static final int INFO_Y4 = 51;
    public static final int LABEL_DEPOSIT_Y = 62;
    public static final int BUTTON_DEPOSIT_Y = 71;
    public static final int LABEL_WITHDRAW_Y = 93;
    public static final int BUTTON_WITHDRAW_Y = 102;
    public static final int BUTTON_CUSTOM_Y = 125;
    public static final int BUTTON_ADMIN_Y = 71;
    public static final int CONTENT_BG_BOTTOM = 167;
    public static final int QUICK_SLOTS = BankService.MAX_QUICK;
    public static final int QUICK_W = 30;
    public static final int QUICK_STEP = 32;
    public static final int CUSTOM_W = 50;
    public static final int CUSTOM_STEP = 54;
    public static final int BUTTON_H = 20;
    public static final int HISTORY_PER_PAGE = 6;
    public static final int HISTORY_TOP = 21;
    public static final int HISTORY_STEP = 23;
    public static final int HISTORY_LINE2 = 10;

    // ---- 按钮编号（公共 0-9，自定义从 BTN_CUSTOM=10 起）----
    public static final int BTN_QUICK_DEPOSIT = BTN_CUSTOM;              // +i
    public static final int BTN_QUICK_WITHDRAW = BTN_CUSTOM + 5;         // +i
    public static final int BTN_DEPOSIT_ALL = BTN_CUSTOM + 10;
    public static final int BTN_HISTORY = BTN_CUSTOM + 11;
    public static final int BTN_BACK_MAIN = BTN_CUSTOM + 12;
    public static final int BTN_ADMIN_CLEAR = BTN_CUSTOM + 13;
    public static final int BTN_ADMIN_FREEZE = BTN_CUSTOM + 14;
    /** 下面三个只在客户端触发文本输入框，不会发到服务端；留号避免和别的功能撞车。 */
    public static final int BTN_CLIENT_DEPOSIT = BTN_CUSTOM + 15;
    public static final int BTN_CLIENT_WITHDRAW = BTN_CUSTOM + 16;
    public static final int BTN_CLIENT_ADMIN = BTN_CUSTOM + 17;

    // ------------------------------------------------------------- 客户端数据

    private final int mode;
    private final boolean selfView;
    private final boolean admin;
    private final long principal;
    private final long minDeposit;
    private final long maxPrincipal;
    private final long rateBps;
    private final long feeBps;
    private final long interestDelay;
    private final long withdrawDelay;
    private final boolean frozen;
    private final long[] quick;
    private final List<BankData.Entry> history;
    private final int historyTotal;

    /** 服务端专用：被查看的账户；客户端为 null。 */
    private final ServerPlayer target;

    // ------------------------------------------------------------- 构造

    /** 客户端构造：参数顺序固定 (containerId, inventory, extraData)。 */
    public BankMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(BankMenus.BANK_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        this.target = null;
        this.mode = buf.readInt();
        this.selfView = buf.readBoolean();
        this.admin = buf.readBoolean();
        this.principal = buf.readLong();
        this.minDeposit = buf.readLong();
        this.maxPrincipal = buf.readLong();
        this.rateBps = buf.readLong();
        this.feeBps = buf.readLong();
        this.interestDelay = buf.readLong();
        this.withdrawDelay = buf.readLong();
        this.frozen = buf.readBoolean();
        int quickCount = clampCount(buf.readInt(), QUICK_SLOTS);
        long[] quicks = new long[quickCount];
        for (int i = 0; i < quickCount; i++) {
            quicks[i] = buf.readLong();
        }
        this.quick = quicks;
        int rows = clampCount(buf.readInt(), HISTORY_PER_PAGE);
        List<BankData.Entry> rowsOut = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            rowsOut.add(new BankData.Entry(buf.readLong(), buf.readUtf(64), buf.readLong(), buf.readLong()));
        }
        this.history = rowsOut;
        this.historyTotal = Math.max(0, buf.readInt());
    }

    private static int clampCount(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    /** 服务端构造。 */
    public BankMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer target,
                    int mode, int page, int pages, boolean manage, boolean admin, boolean selfView,
                    int historyTotal) {
        super(BankMenus.BANK_MENU.get(), containerId, inventory, viewer, page, pages, manage);
        this.target = target;
        this.mode = mode;
        this.selfView = selfView;
        this.admin = admin;
        this.historyTotal = Math.max(0, historyTotal);
        BankData data = BankService.data(target);
        this.principal = data.principal();
        this.frozen = data.frozen();
        this.minDeposit = SuiteConfig.bankMinDeposit();
        this.maxPrincipal = SuiteConfig.bankMaxPrincipal();
        this.rateBps = BankService.rateBps();
        this.feeBps = BankService.feeBps();
        this.interestDelay = BankService.nextInterestDelay(target);
        this.withdrawDelay = BankService.withdrawDelay(target);
        this.quick = BankService.quickAmounts();
        this.history = mode == MODE_HISTORY
                ? historyRows(data, page, historyTotal)
                : List.of();
    }

    /**
     * 写 openMenu 的附加数据：公共头之后依次是
     * mode / self / admin / principal / minDeposit / maxPrincipal / rateBps / feeBps /
     * interestDelay / withdrawDelay / frozen / quickCount+quick[] / rowCount+rows[] / historyTotal。
     * 客户端读取顺序必须与此完全一致。
     */
    public static void writeBankData(RegistryFriendlyByteBuf buf, ServerPlayer viewer, ServerPlayer target,
                                     int mode, int page, int pages, boolean manage, boolean admin,
                                     boolean selfView, int historyTotal) {
        BankData data = BankService.data(target);
        writeHeader(buf, Economy.balance(viewer), page, pages, manage, 0);
        buf.writeInt(mode);
        buf.writeBoolean(selfView);
        buf.writeBoolean(admin);
        buf.writeLong(data.principal());
        buf.writeLong(SuiteConfig.bankMinDeposit());
        buf.writeLong(SuiteConfig.bankMaxPrincipal());
        buf.writeLong(BankService.rateBps());
        buf.writeLong(BankService.feeBps());
        buf.writeLong(BankService.nextInterestDelay(target));
        buf.writeLong(BankService.withdrawDelay(target));
        buf.writeBoolean(data.frozen());
        long[] quick = BankService.quickAmounts();
        buf.writeInt(quick.length);
        for (long value : quick) {
            buf.writeLong(value);
        }
        List<BankData.Entry> rows = mode == MODE_HISTORY ? historyRows(data, page, historyTotal) : List.of();
        buf.writeInt(rows.size());
        for (BankData.Entry entry : rows) {
            buf.writeLong(entry.time());
            buf.writeUtf(entry.reason() == null ? "" : entry.reason());
            buf.writeLong(entry.amount());
            buf.writeLong(entry.balance());
        }
        buf.writeInt(Math.max(0, historyTotal));
    }

    private static List<BankData.Entry> historyRows(BankData data, int page, int historyTotal) {
        List<BankData.Entry> all = data.history();
        int from = Math.max(0, page) * HISTORY_PER_PAGE;
        int to = Math.min(Math.max(0, historyTotal), from + HISTORY_PER_PAGE);
        List<BankData.Entry> rows = new ArrayList<>();
        for (int i = from; i < to && i < all.size(); i++) {
            rows.add(all.get(i));
        }
        return rows;
    }

    // ------------------------------------------------------------- 按钮

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (buttonId == BTN_HISTORY) {
            BankService.openHistory(viewer, targetOrSelf(viewer), 0);
            return true;
        }
        if (buttonId == BTN_BACK_MAIN) {
            BankService.open(viewer, targetOrSelf(viewer), 0);
            return true;
        }
        if (mode == MODE_HISTORY) {
            return false;                       // 流水页没有其它写动作
        }
        if (!selfView) {
            // 他人账户页上的管理按钮只允许 adminPermission，具体判定在 BankService 里再做一遍
            if (buttonId != BTN_ADMIN_CLEAR && buttonId != BTN_ADMIN_FREEZE) {
                return false;
            }
        }
        long[] quick = BankService.quickAmounts();
        if (buttonId >= BTN_QUICK_DEPOSIT && buttonId < BTN_QUICK_DEPOSIT + quick.length) {
            if (BankService.deposit(viewer, quick[buttonId - BTN_QUICK_DEPOSIT])) {
                BankService.refresh(viewer);
                return true;
            }
            return false;
        }
        if (buttonId >= BTN_QUICK_WITHDRAW && buttonId < BTN_QUICK_WITHDRAW + quick.length) {
            if (BankService.withdraw(viewer, quick[buttonId - BTN_QUICK_WITHDRAW])) {
                BankService.refresh(viewer);
                return true;
            }
            return false;
        }
        if (buttonId == BTN_DEPOSIT_ALL) {
            // 全部存入：取余额与剩余可存额度的较小值（仍然走同一套校验，不绕开上限）。
            long amount = Math.min(Economy.balance(viewer), BankService.data(viewer).headroom());
            if (amount <= 0L) {
                amount = Math.max(1L, Economy.balance(viewer));
            }
            if (BankService.deposit(viewer, amount)) {
                BankService.refresh(viewer);
                return true;
            }
            return false;
        }
        if (buttonId == BTN_ADMIN_CLEAR) {
            if (BankService.adminClearPrincipal(viewer, targetOrSelf(viewer))) {
                BankService.refresh(viewer);
                return true;
            }
            return false;
        }
        if (buttonId == BTN_ADMIN_FREEZE) {
            if (BankService.adminToggleFreeze(viewer, targetOrSelf(viewer))) {
                BankService.refresh(viewer);
                return true;
            }
            return false;
        }
        // 自定义金额与他人账户查询都走文本输入，不发按钮。
        return false;
    }

    private ServerPlayer targetOrSelf(ServerPlayer viewer) {
        return target != null ? target : viewer;
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        BankService.openMode(viewer, targetOrSelf(viewer), page, mode);
    }

    // ------------------------------------------------------------- 只读访问

    public int mode() {
        return mode;
    }

    public boolean isHistory() {
        return mode == MODE_HISTORY;
    }

    public boolean selfView() {
        return selfView;
    }

    public boolean admin() {
        return admin;
    }

    public long principal() {
        return Math.max(0L, principal);
    }

    public long minDeposit() {
        return minDeposit;
    }

    public long maxPrincipal() {
        return maxPrincipal;
    }

    public long rateBps() {
        return rateBps;
    }

    public long feeBps() {
        return feeBps;
    }

    /** 距下次结息剩余毫秒；-1 表示不计时（未开始计息或利率为 0）。 */
    public long interestDelay() {
        return interestDelay;
    }

    /** 距可再次取款剩余毫秒；0 表示随时可取。 */
    public long withdrawDelay() {
        return withdrawDelay;
    }

    public boolean frozen() {
        return frozen;
    }

    public long[] quickAmounts() {
        return quick;
    }

    public List<BankData.Entry> history() {
        return history;
    }

    public int historyTotal() {
        return historyTotal;
    }

    /** 第 index 行流水的显示用余额（本金口径）。 */
    public long historyBalance(int index) {
        return index >= 0 && index < history.size() ? history.get(index).balance() : 0L;
    }

    /** 服务端才有；客户端为 null。 */
    public ServerPlayer target() {
        return target;
    }

    /** 利率/手续费的百分比文本，例如万分比 100 -> "1.00%"。 */
    public static String percent(long bps) {
        long whole = bps / 100L;
        long frac = Math.abs(bps % 100L);
        return whole + "." + (frac < 10L ? "0" + frac : Long.toString(frac)) + "%";
    }

    /** 毫秒倒计时 -> "1天2小时3分"（不足 1 分钟显示秒）。 */
    public static String countdown(long millis) {
        if (millis < 0L) {
            return "-";
        }
        long seconds = millis / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0L) {
            return days + "d" + (hours % 24L) + "h";
        }
        if (hours > 0L) {
            return hours + "h" + (minutes % 60L) + "m";
        }
        return minutes + "m";
    }
}
