package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.DisplaySlots;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方市场容器：一页 = 分类标签（2 行 × 5 个，可 ‹› 翻窗）+ 每页 3 行商品，
 * 行内第三行是「买1/买16/买64/卖1/卖64/买N/卖N」按钮；OP 管理模式下换成
 * 「改买价/改卖价/改库存/补货/禁用」按钮。
 *
 * <p><b>按钮编号（客户端 Screen 与服务端 onAction 共用，全部从 {@code BTN_CUSTOM=10} 起）</b>：
 * <pre>
 * 10..19  分类标签（BTN_CATEGORY_BASE，0 = 全部，1..9 = 第 i 个分类）
 * 30      分类标签窗口上一页        31  分类标签窗口下一页
 * 32      管理模式开关              33  一键全店补货      34  重载目录
 * 40..60  交易按钮（BTN_TRADE_BASE）= 40 + 行*7 + 档（行 0..2；档 0..4 = 买1/买16/买64/卖1/卖64；
 *         档 5/6 = 买N/卖N，客户端只开输入框不下发按钮号，服务端收到也忽略）
 * 70..84  管理按钮（BTN_MANAGE_BASE）= 70 + 行*5 + 动作
 *         （0 改买价 1 改卖价 2 改库存 3 补货该条 4 禁用/启用；0/1/2 客户端只开输入框）
 * </pre>
 * 其中 0/1/2 档与 3/4 档直接成交；5/6 档和管理动作 0/1/2 只是打开输入框，
 * 真正的数量/价格走文本输入通道（action 名带行号，见 {@link MarketService}）。
 *
 * <p><b>公共头之后的 sub int（写入顺序 = 客户端读取顺序）</b>：
 * {@code rows, flags, chipWindow, chipCount, categoryIndex, statsEntries, statsTracked,
 * statsDisabled, statsStock, dailyRemain}；
 * 随后是 {@code chipCount} 个分类名（utf），再按行写
 * {@code entryId(utf), buy(long), sell(long), stock(int), dailyLeft(int), unit(int),
 * disabled(int), customized(int)}。
 */
public class MarketMenu extends PageMenu {
    // ------------------------------------------------------- 界面几何（Screen 共用）

    public static final int PANEL_WIDTH = 250;
    public static final int CHIP_TOP = Layout.HEADER + 3;
    public static final int CHIP_H = 14;
    public static final int CHIP_PITCH = 16;
    public static final int CHIPS_PER_ROW = 5;
    public static final int CHIP_ROWS = 2;
    /** 分类标签窗口大小（两行 × 每行 5 个）。 */
    public static final int CHIP_WINDOW = CHIPS_PER_ROW * CHIP_ROWS;
    public static final int CONTENT_TOP = CHIP_TOP + CHIP_ROWS * CHIP_PITCH + 4;
    public static final int ROW_H = 40;
    /** 每页商品行数（3 行 × 40 = 120，保证整块面板在 240 像素内，小窗口也不裁切）。 */
    public static final int MAX_ROWS = 3;
    /** 每行交易按钮档数。 */
    public static final int TRADE_SLOTS = 7;
    /** 每行管理按钮数。 */
    public static final int MANAGE_SLOTS = 5;
    public static final int SLOT_X = Layout.GRID_LEFT;
    public static final int TEXT_X = 30;
    public static final int CHIP_W = 46;
    public static final int CHIP_GAP = 2;
    public static final int BUTTON_GAP = 2;
    public static final int BUTTON_H = 14;
    /** 交易按钮宽度（买1/买16/买64/卖1/卖64/自买/自卖）。 */
    public static final int[] TRADE_W = {26, 30, 30, 26, 30, 26, 26};
    /** 管理按钮宽度（改买价/改卖价/改库存/补货/禁用）。 */
    public static final int[] MANAGE_W = {46, 46, 46, 34, 38};

    // ------------------------------------------------------------- flags 位

    /** OP 达到 adminPermission，可以改价/库存/禁用/补货/重载。 */
    public static final int FLAG_EDIT = 1;
    /** 当前处于管理模式（显示管理按钮）。 */
    public static final int FLAG_ADMIN_MODE = 2;
    /** marketBuyEnabled。 */
    public static final int FLAG_BUY = 4;
    /** marketSellEnabled。 */
    public static final int FLAG_SELL = 8;
    /** marketStockEnabled。 */
    public static final int FLAG_STOCK = 16;
    /** marketDailyBuyLimit() > 0。 */
    public static final int FLAG_DAILY = 32;
    /** 目录为空（全灰提示）。 */
    public static final int FLAG_EMPTY = 64;

    // ------------------------------------------------------------- 按钮编号

    public static final int BTN_CATEGORY_BASE = BTN_CUSTOM;        // 10..19
    public static final int BTN_CHIP_PREV = BTN_CUSTOM + 20;        // 30
    public static final int BTN_CHIP_NEXT = BTN_CUSTOM + 21;        // 31
    public static final int BTN_ADMIN_MODE = BTN_CUSTOM + 22;       // 32
    public static final int BTN_RESTOCK_ALL = BTN_CUSTOM + 23;      // 33
    public static final int BTN_RELOAD = BTN_CUSTOM + 24;           // 34
    public static final int BTN_TRADE_BASE = BTN_CUSTOM + 30;       // 40..60
    public static final int BTN_MANAGE_BASE = BTN_CUSTOM + 60;      // 70..84

    /** 交易档位对应的份数（档 5/6 = 自定义，由输入框给数量）。 */
    public static final int[] TRADE_TIERS = {1, 16, 64, 1, 64, 0, 0};
    /** true = 该档是卖出。 */
    public static final boolean[] TRADE_SELL = {false, false, false, true, true, false, true};
    /** 自定义数量档（只开输入框，不下发按钮号）。 */
    public static final int TIER_CUSTOM_BUY = 5;
    public static final int TIER_CUSTOM_SELL = 6;

    private static final int SUB_INTS = 10;
    /** 分类标签总数上限（含「全部」，超出的分类既不下发也不显示）。 */
    public static final int MAX_CHIPS = 32;

    private final int rows;
    private final int flags;
    private final int chipWindow;
    private final int categoryIndex;
    private final int statsEntries;
    private final int statsTracked;
    private final int statsDisabled;
    private final int statsStock;
    private final int dailyRemain;
    private final List<String> chips = new ArrayList<>();
    private final String[] rowIds;
    private final long[] rowBuy;
    private final long[] rowSell;
    private final int[] rowStock;
    private final int[] rowDaily;
    private final int[] rowUnit;
    private final boolean[] rowDisabled;
    private final boolean[] rowCustomized;

    /** 服务端：当前页每个条目 id（解析按钮行号用）；客户端为 null。 */
    private final ServerPlayer viewerPlayer;

    // ------------------------------------------------------------- 构造

    /** 客户端构造。 */
    public MarketMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(MarketMenus.MARKET_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        int[] sub = readSubData(buf, SUB_INTS);
        this.rows = Math.max(0, Math.min(MAX_ROWS, sub[0]));
        this.flags = sub[1];
        this.chipWindow = Math.max(0, sub[2]);
        int chipCount = Math.max(0, Math.min(MAX_CHIPS, sub[3]));
        this.categoryIndex = sub[4];
        this.statsEntries = sub[5];
        this.statsTracked = sub[6];
        this.statsDisabled = sub[7];
        this.statsStock = sub[8];
        this.dailyRemain = sub[9];
        this.viewerPlayer = null;
        this.rowIds = new String[rows];
        this.rowBuy = new long[rows];
        this.rowSell = new long[rows];
        this.rowStock = new int[rows];
        this.rowDaily = new int[rows];
        this.rowUnit = new int[rows];
        this.rowDisabled = new boolean[rows];
        this.rowCustomized = new boolean[rows];
        for (int i = 0; i < chipCount; i++) {
            chips.add(buf.readUtf(256));
        }
        for (int i = 0; i < rows; i++) {
            rowIds[i] = buf.readUtf(128);
            rowBuy[i] = buf.readLong();
            rowSell[i] = buf.readLong();
            rowStock[i] = buf.readInt();
            rowDaily[i] = buf.readInt();
            rowUnit[i] = buf.readInt();
            rowDisabled[i] = buf.readInt() != 0;
            rowCustomized[i] = buf.readInt() != 0;
            addSlot(DisplaySlots.empty(SLOT_X, slotY(i)));
        }
    }

    /** 服务端构造。 */
    MarketMenu(int containerId, Inventory inventory, ServerPlayer viewer, PageData data) {
        super(MarketMenus.MARKET_MENU.get(), containerId, inventory, viewer,
                data.page, data.pages, data.manage);
        this.rows = data.rows.size();
        this.flags = data.flags;
        this.chipWindow = data.chipWindow;
        this.categoryIndex = data.categoryIndex;
        this.statsEntries = data.statsEntries;
        this.statsTracked = data.statsTracked;
        this.statsDisabled = data.statsDisabled;
        this.statsStock = data.statsStock;
        this.dailyRemain = data.dailyRemain;
        this.chips.addAll(data.chips);
        this.viewerPlayer = viewer;
        this.rowIds = new String[this.rows];
        this.rowBuy = new long[this.rows];
        this.rowSell = new long[this.rows];
        this.rowStock = new int[this.rows];
        this.rowDaily = new int[this.rows];
        this.rowUnit = new int[this.rows];
        this.rowDisabled = new boolean[this.rows];
        this.rowCustomized = new boolean[this.rows];
        for (int i = 0; i < this.rows; i++) {
            PageRow row = data.rows.get(i);
            rowIds[i] = row.id;
            rowBuy[i] = row.buy;
            rowSell[i] = row.sell;
            rowStock[i] = row.stockLeft;
            rowDaily[i] = row.dailyLeft;
            rowUnit[i] = row.unit;
            rowDisabled[i] = row.disabled;
            rowCustomized[i] = row.customized;
            addSlot(DisplaySlots.of(row.icon, SLOT_X, slotY(i)));
        }
    }

    /** 供 {@link MarketService} 写 openMenu 附加数据（writeFull 是 protected 静态）。 */
    static void writeTo(RegistryFriendlyByteBuf buf, PageData data) {
        writeFull(buf, data.balance, data.page, data.pages, data.manage, 0,
                data.rows.size(), data.flags, data.chipWindow, data.chips.size(), data.categoryIndex,
                data.statsEntries, data.statsTracked, data.statsDisabled, data.statsStock, data.dailyRemain);
        for (String chip : data.chips) {
            buf.writeUtf(chip);
        }
        for (PageRow row : data.rows) {
            buf.writeUtf(row.id);
            buf.writeLong(row.buy);
            buf.writeLong(row.sell);
            buf.writeInt(row.stockLeft);
            buf.writeInt(row.dailyLeft);
            buf.writeInt(row.unit);
            buf.writeInt(row.disabled ? 1 : 0);
            buf.writeInt(row.customized ? 1 : 0);
        }
    }

    // ------------------------------------------------------------- 几何工具

    public static int rowTop(int row) {
        return CONTENT_TOP + row * ROW_H;
    }

    /** 第 row 行展示图标的槽位 y（服务端加槽与客户端绘制共用）。 */
    public static int slotY(int row) {
        return rowTop(row) + 2;
    }

    /** 第 row 行第一行文字（名称）的 y。 */
    public static int nameY(int row) {
        return rowTop(row) + 3;
    }

    /** 第 row 行第二行文字（价格/库存）的 y。 */
    public static int infoY(int row) {
        return rowTop(row) + 13;
    }

    /** 第 row 行按钮行的 y。 */
    public static int rowButtonY(int row) {
        return rowTop(row) + 24;
    }

    // ---- 行内文字列位置（第一行：名称/份数/库存/今日剩余；第二行：买价/卖价/已改价）----
    public static final int COL_NAME = 30;
    public static final int COL_UNIT = 132;
    public static final int COL_STOCK = 168;
    public static final int COL_DAILY = 210;
    public static final int COL_BUY = 30;
    public static final int COL_SELL = 100;
    public static final int COL_MARK = 170;
    /** 第一/二行文字宽度上限。 */
    public static final int NAME_W = 100;

    public static int footerRow1(int rows) {
        return CONTENT_TOP + Math.max(1, rows) * ROW_H + 6;
    }

    public static int footerRow2(int rows) {
        return footerRow1(rows) + 22;
    }

    /** 第三行底部文字（今日剩余 / OP 统计）的 y。 */
    public static int footerRow3(int rows) {
        return footerRow2(rows) + 22;
    }

    public static int panelHeight(int rows) {
        return footerRow3(rows) + 12;
    }

    public static int footerRow1() {
        return footerRow1(MAX_ROWS);
    }

    /** 第 index 个分类标签的 x（两行 × 每行 5 个）。 */
    public static int chipX(int indexInWindow) {
        int slot = indexInWindow % CHIPS_PER_ROW;
        return Layout.GRID_LEFT + slot * (CHIP_W + CHIP_GAP);
    }

    /** 第 index 个分类标签的 y。 */
    public static int chipY(int indexInWindow) {
        return CHIP_TOP + (indexInWindow / CHIPS_PER_ROW) * CHIP_PITCH;
    }

    public static int chipWidth() {
        return CHIP_W;
    }

    /** 行内交易按钮整排的起始 x（按钮单独占一行，从左边距开始）。 */
    public static int tradeButtonsX() {
        return Layout.GRID_LEFT;
    }

    /** 行内管理按钮整排的起始 x。 */
    public static int manageButtonsX() {
        return Layout.GRID_LEFT;
    }

    private static int rowWidth(int[] widths) {
        int total = 0;
        for (int i = 0; i < widths.length; i++) {
            total += widths[i] + (i > 0 ? BUTTON_GAP : 0);
        }
        return total;
    }



    // ------------------------------------------------------------- 只读访问

    public int rows() {
        return rows;
    }

    public int flags() {
        return flags;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public int chipWindow() {
        return chipWindow;
    }

    public int categoryIndex() {
        return categoryIndex;
    }

    public List<String> chips() {
        return chips;
    }

    public int statsEntries() {
        return statsEntries;
    }

    public int statsTracked() {
        return statsTracked;
    }

    public int statsDisabled() {
        return statsDisabled;
    }

    public int statsStock() {
        return statsStock;
    }

    /** 今日剩余可买份数（-1 = 不限购）。 */
    public int dailyRemaining() {
        return dailyRemain;
    }

    public String rowId(int row) {
        return row >= 0 && row < rowIds.length ? rowIds[row] : "";
    }

    public long rowBuy(int row) {
        return row >= 0 && row < rowBuy.length ? rowBuy[row] : 0L;
    }

    public long rowSell(int row) {
        return row >= 0 && row < rowSell.length ? rowSell[row] : 0L;
    }

    /** 剩余库存（-1 = 不限）。 */
    public int rowStock(int row) {
        return row >= 0 && row < rowStock.length ? rowStock[row] : -1;
    }

    /** 该玩家该条目今日剩余可买份数（-1 = 不限购）。 */
    public int rowDailyLeft(int row) {
        return row >= 0 && row < rowDaily.length ? rowDaily[row] : -1;
    }

    /** 一份含多少件物品。 */
    public int rowUnit(int row) {
        return row >= 0 && row < rowUnit.length ? Math.max(1, rowUnit[row]) : 1;
    }

    public boolean rowDisabled(int row) {
        return row >= 0 && row < rowDisabled.length && rowDisabled[row];
    }

    /** 该条目是否被 OP 改过价。 */
    public boolean rowCustomized(int row) {
        return row >= 0 && row < rowCustomized.length && rowCustomized[row];
    }

    /** 行图标（只读展示槽，永远拿不出来）。 */
    public ItemStack rowDisplay(int row) {
        if (row < 0 || row >= rows || row >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(row).getItem();
    }

    // ------------------------------------------------------------- 按钮分发

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (viewer.isRemoved()) {
            return false;
        }
        if (buttonId >= BTN_CATEGORY_BASE && buttonId < BTN_CATEGORY_BASE + CHIP_WINDOW) {
            MarketService.selectCategory(viewer, buttonId - BTN_CATEGORY_BASE);
            return true;
        }
        if (buttonId == BTN_CHIP_PREV) {
            MarketService.shiftChips(viewer, -1);
            return true;
        }
        if (buttonId == BTN_CHIP_NEXT) {
            MarketService.shiftChips(viewer, 1);
            return true;
        }
        if (buttonId == BTN_ADMIN_MODE) {
            MarketService.toggleAdminMode(viewer);
            return true;
        }
        if (buttonId == BTN_RESTOCK_ALL) {
            MarketService.restockAll(viewer);
            return true;
        }
        if (buttonId == BTN_RELOAD) {
            MarketService.reloadCatalog(viewer);
            return true;
        }
        if (buttonId >= BTN_TRADE_BASE && buttonId < BTN_TRADE_BASE + MAX_ROWS * TRADE_SLOTS) {
            int rel = buttonId - BTN_TRADE_BASE;
            int row = rel / TRADE_SLOTS;
            int tier = rel % TRADE_SLOTS;
            if (row < 0 || row >= rows || tier < 0 || tier >= TRADE_SLOTS) {
                return false;
            }
            if (tier >= TIER_CUSTOM_BUY) {
                // 自定义数量由客户端直接开输入框（不下发按钮号）；收到就忽略
                return false;
            }
            MarketService.trade(viewer, rowIds[row], TRADE_SELL[tier], TRADE_TIERS[tier]);
            return true;
        }
        if (buttonId >= BTN_MANAGE_BASE && buttonId < BTN_MANAGE_BASE + MAX_ROWS * MANAGE_SLOTS) {
            int rel = buttonId - BTN_MANAGE_BASE;
            int row = rel / MANAGE_SLOTS;
            int action = rel % MANAGE_SLOTS;
            if (row < 0 || row >= rows) {
                return false;
            }
            MarketService.manageButton(viewer, rowIds[row], action);
            return true;
        }
        return false;
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        MarketService.openPage(viewer, page, categoryIndex, chipWindow, hasFlag(FLAG_ADMIN_MODE));
    }

    /** 服务端：这个菜单的查看者（客户端为 null）。 */
    public ServerPlayer viewerPlayer() {
        return viewerPlayer;
    }

    /** 该档位是不是卖出。 */
    public static boolean isSellSlot(int tier) {
        return tier >= 0 && tier < TRADE_SELL.length && TRADE_SELL[tier];
    }

    // ------------------------------------------------------------- 页数据载体

    /** 一行商品的展示数据（服务端组装，客户端从 buf 还原）。 */
    static final class PageRow {
        String id = "";
        long buy;
        long sell;
        int stockLeft = -1;
        int dailyLeft = -1;
        int unit = 1;
        boolean disabled;
        /** OP 改过价（显示「已改价」标记）。 */
        boolean customized;
        ItemStack icon = ItemStack.EMPTY;
    }

    /** 一次 openMenu 的全部下发数据。 */
    static final class PageData {
        long balance;
        int page;
        int pages = 1;
        int flags;
        int chipWindow;
        int categoryIndex;
        int statsEntries;
        int statsTracked;
        int statsDisabled;
        int statsStock;
        int dailyRemain = -1;
        boolean manage;
        final List<String> chips = new ArrayList<>();
        final List<PageRow> rows = new ArrayList<>();

        int rowCount() {
            return rows.size();
        }
    }
}
