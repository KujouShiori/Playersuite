package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官方市场业务中枢（服务端权威）：目录展示、买/卖结算、库存与每日限购、
 * OP 管理（改买价 / 改卖价 / 改库存 / 临时禁用 / 一键补货 / 重载目录）与统计。
 *
 * <p>接线点（已在 {@code PlayerSuiteMod}、{@code FeatureOpeners}、{@code InputRouter} 中完成）：
 * <ul>
 *     <li>{@code FeatureOpeners.open}：{@code case FeatureOpeners.MARKET -> MarketService.open(player, player, page);}</li>
 *     <li>{@code network/InputRouter.handle}：{@code case FeatureOpeners.MARKET -> MarketService.onTextInput(sender, action, text);}</li>
 *     <li>模组构造方法：{@code MarketMenus.register(modEventBus);}（本功能不需要玩家附件，故无 {@code MarketAttachments}）</li>
 *     <li>指令：{@code MarketCommand.register(dispatcher);}（{@code /market}，见 {@code command/MarketCommand}）</li>
 * </ul>
 *
 * <p>设计参考本地 TradeSquares（MIT）的「服务端目录 give/cost + SavedData 库存 + 全量校验后统一结算」思路，
 * 代码为 playersuite 自有实现（不同包、复用 {@code PageMenu}/{@code Economy}）。
 *
 * <p>单位约定：<b>份</b> = 条目的一次交易单位（一 {@code unit} 件物品）；
 * 界面按钮「买 1 / 16 / 64」都是份数；买价/卖价都是每份价；库存以份计；
 * 每日限购以<b>件数</b>计（对应配置 {@code dailyBuyLimit} 的注释）。
 *
 * <p>所有方法都只在逻辑服务端调用。
 */
public final class MarketService {
    /** 功能键（同 {@code FeatureOpeners.MARKET}）。 */
    public static final String FEATURE = FeatureKeys.MARKET;

    // ---- 文本输入 action 前缀（后面一律带页内行号 0..3）----
    /** 自定义买入份数。 */
    public static final String ACTION_BUY_QTY = "buy_qty_";
    /** 自定义卖出份数。 */
    public static final String ACTION_SELL_QTY = "sell_qty_";
    /** OP 改买价。 */
    public static final String ACTION_PRICE_BUY = "price_buy_";
    /** OP 改卖价。 */
    public static final String ACTION_PRICE_SELL = "price_sell_";
    /** OP 改库存。 */
    public static final String ACTION_STOCK = "stock_";

    /** 单笔交易的份数上限（同时也是界面档位上限）。 */
    public static final int MAX_UNITS = 64;
    /** 单笔交易的件数上限（份数 × 每件数量），防背包炸开与 int 溢出。 */
    public static final int MAX_ITEMS_PER_TRADE = 2304;
    /** 限购剩余件数少于该值时视为「今天买不下了」。 */
    private static final int DAILY_SLACK = 0;

    /** 每玩家的界面状态（分类 / 标签窗口 / 管理模式 / 页码）；登出即清。 */
    private static final Map<UUID, UiState> UI = new ConcurrentHashMap<>();
    /** 防连点：同一玩家同一 tick 只结算一笔。 */
    private static final Map<UUID, Integer> LAST_TRADE_TICK = new ConcurrentHashMap<>();

    private MarketService() {
    }

    // ---------------------------------------------------------------- UI 状态

    private static final class UiState {
        int category;
        int chipWindow;
        boolean adminMode;
        int page;
    }

    private static UiState ui(ServerPlayer player) {
        return UI.computeIfAbsent(player.getUUID(), key -> new UiState());
    }

    /** 玩家登出/换服时清理内存状态（由 {@link MarketEvents} 调用）。 */
    public static void forget(UUID player) {
        if (player != null) {
            UI.remove(player);
            LAST_TRADE_TICK.remove(player);
        }
    }

    // ---------------------------------------------------------------- 打开页面

    /**
     * 打开官方市场。市场是全局页面，{@code target} 仅用于满足统一打开签名，实际忽略。
     *
     * @param page 起始页码（自动夹到有效范围）
     */
    public static void open(ServerPlayer viewer, ServerPlayer target, int page) {
        if (viewer == null || viewer.isRemoved()) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.MARKET)) {
            say(viewer, "playersuite.market.msg.featureOff");
            return;
        }
        UiState state = ui(viewer);
        state.page = Math.max(0, page);
        openPage(viewer, state.page, state.category, state.chipWindow, state.adminMode);
    }

    /** 分类标签总数（0 = 全部 + 各分类）。 */
    public static int categoryCount() {
        return Math.min(MarketMenu.MAX_CHIPS, MarketCatalog.categories().size() + 1);
    }

    /**
     * 打开某一页。
     *
     * @param category   分类下标（0 = 全部，1.. = {@code marketCategories()} 顺序）
     * @param chipWindow 分类标签窗口序号
     * @param adminMode  是否处于 OP 管理模式
     */
    public static void openPage(ServerPlayer viewer, int page, int category, int chipWindow, boolean adminMode) {
        if (viewer == null || viewer.isRemoved()) {
            return;
        }
        MinecraftServer server = viewer.server;
        String categoryName = categoryOf(category);
        List<MarketCatalog.Entry> listed = MarketCatalog.inCategory(categoryName);
        // 被临时禁用的条目只对 OP 显示（普通玩家看不到，也就点不到）
        boolean op = viewer.hasPermissions(SuiteConfig.managePermission());
        List<MarketCatalog.Entry> all = new java.util.ArrayList<>(listed.size());
        for (MarketCatalog.Entry entry : listed) {
            MarketState.EntryState peekState = MarketState.of(server).peek(entry.id());
            if (!op && peekState != null && peekState.disabled()) {
                continue;
            }
            all.add(entry);
        }
        int rows = MarketMenu.MAX_ROWS;
        int pages = Math.max(1, (all.size() + rows - 1) / rows);
        int clamped = Math.max(0, Math.min(page, pages - 1));
        int from = clamped * rows;
        int rowCount = Math.max(0, Math.min(rows, all.size() - from));

        MarketState state = MarketState.of(server);
        long now = MarketState.dayTime(server);
        boolean byTime = MarketState.restockTicks() > 0L;
        int dailyLimit = SuiteConfig.marketDailyBuyLimit();
        long day = MarketState.dayIndex(server);
        int boughtToday = state.boughtToday(viewer.getUUID(), day);

        MarketMenu.PageData data = new MarketMenu.PageData();
        data.balance = Economy.balance(viewer);
        data.page = clamped;
        data.pages = pages;
        data.categoryIndex = category;
        data.chipWindow = Math.max(0, chipWindow);
        data.manage = viewer.hasPermissions(SuiteConfig.managePermission());

        int flags = 0;
        if (viewer.hasPermissions(SuiteConfig.adminPermission())) {
            flags |= MarketMenu.FLAG_EDIT;
        }
        if (adminMode) {
            flags |= MarketMenu.FLAG_ADMIN_MODE;
        }
        if (SuiteConfig.marketBuyEnabled()) {
            flags |= MarketMenu.FLAG_BUY;
        }
        if (SuiteConfig.marketSellEnabled()) {
            flags |= MarketMenu.FLAG_SELL;
        }
        if (MarketState.stockEnabled()) {
            flags |= MarketMenu.FLAG_STOCK;
        }
        if (dailyLimit > 0) {
            flags |= MarketMenu.FLAG_DAILY;
            data.dailyRemain = Math.max(DAILY_SLACK, dailyLimit - boughtToday);
        } else {
            data.dailyRemain = -1;
        }
        if (all.isEmpty()) {
            flags |= MarketMenu.FLAG_EMPTY;
        }
        data.flags = flags;

        for (int i = 0; i < rowCount; i++) {
            MarketCatalog.Entry entry = all.get(from + i);
            MarketState.EntryState entryState = state.stateFor(entry, now, byTime);
            MarketMenu.PageRow row = new MarketMenu.PageRow();
            row.id = entry.id();
            row.unit = Math.max(1, entry.unit());
            row.buy = state.buyPrice(entry, entryState);
            row.sell = state.sellPrice(entry, entryState);
            row.stockLeft = state.displayStock(entry, entryState);
            row.disabled = entryState != null && entryState.disabled();
            row.dailyLeft = dailyLimit > 0 ? Math.max(0, dailyLimit - boughtToday) : -1;
            row.icon = icon(entry, row.unit);
            row.customized = entryState != null
                    && (entryState.buyOverride() != MarketState.NO_OVERRIDE
                    || entryState.sellOverride() != MarketState.NO_OVERRIDE);
            data.rows.add(row);
        }

        // 分类标签（0 = 全部，其余按配置顺序；超出 MAX_CHIPS 的不发也不显示）
        data.chips.add(MarketCatalog.CATEGORY_ALL);
        for (String name : MarketCatalog.categories()) {
            if (data.chips.size() >= MarketMenu.MAX_CHIPS) {
                break;
            }
            data.chips.add(name);
        }

        // OP 统计（全目录）
        int disabled = 0;
        long stockSum = 0L;
        for (MarketCatalog.Entry entry : MarketCatalog.entries()) {
            MarketState.EntryState es = state.stateFor(entry, now, byTime);
            if (es != null && es.disabled()) {
                disabled++;
            }
            int left = state.displayStock(entry, es);
            if (left > 0) {
                stockSum = Math.min(Integer.MAX_VALUE, stockSum + left);
            }
        }
        data.statsEntries = MarketCatalog.entries().size();
        data.statsDisabled = disabled;
        data.statsStock = (int) stockSum;
        data.statsTracked = state.trackedEntries();

        UiState remembered = ui(viewer);
        remembered.category = category;
        remembered.chipWindow = data.chipWindow;
        remembered.adminMode = adminMode;
        remembered.page = clamped;

        viewer.openMenu(new SimpleMenuProvider(
                        (containerId, inventory, sender) -> new MarketMenu(containerId, inventory, viewer, data),
                        Component.translatable("playersuite.market.title")),
                buf -> MarketMenu.writeTo(buf, data));
    }

    /** 分类下标（0 = 全部）对应的分类名。 */
    private static String categoryOf(int category) {
        if (category <= 0) {
            return MarketCatalog.CATEGORY_ALL;
        }
        List<String> categories = MarketCatalog.categories();
        int index = category - 1;
        return index < categories.size() ? categories.get(index) : MarketCatalog.CATEGORY_ALL;
    }

    /** 展示图标：一 {@code unit} 件物品（只读，拿不出来）。 */
    private static ItemStack icon(MarketCatalog.Entry entry, int unit) {
        ItemStack stack = new ItemStack(entry.item(), 1);
        return stack.copyWithCount(Math.min(64, Math.max(1, unit)));
    }

    // ---------------------------------------------------------------- 分类 / 管理模式

    /** 第 slot 个可见分类标签被点击（0..CHIP_WINDOW-1）。 */
    public static void selectCategory(ServerPlayer viewer, int slot) {
        UiState state = ui(viewer);
        int absolute = state.chipWindow * MarketMenu.CHIP_WINDOW + slot;
        if (absolute < 0 || absolute >= categoryCount()) {
            absolute = 0;
        }
        openPage(viewer, 0, absolute, state.chipWindow, state.adminMode);
    }

    /** 分类标签窗口翻页。 */
    public static void shiftChips(ServerPlayer viewer, int direction) {
        UiState state = ui(viewer);
        int windows = Math.max(1, (categoryCount() + MarketMenu.CHIP_WINDOW - 1) / MarketMenu.CHIP_WINDOW);
        int next = Math.max(0, Math.min(windows - 1, state.chipWindow + direction));
        if (next == state.chipWindow) {
            return;
        }
        openPage(viewer, 0, state.category, next, state.adminMode);
    }

    /** 管理模式开关（按钮只有 managePermission 达标才可见，服务端再校验一次）。 */
    public static void toggleAdminMode(ServerPlayer viewer) {
        if (!viewer.hasPermissions(SuiteConfig.managePermission())) {
            say(viewer, "playersuite.msg.noPermission", SuiteConfig.managePermission());
            return;
        }
        UiState state = ui(viewer);
        openPage(viewer, state.page, state.category, state.chipWindow, !state.adminMode);
    }

    // ---------------------------------------------------------------- 交易

    /**
     * 交易入口（服务端唯一结算通道，按钮和文本输入都收口到这里）。
     *
     * @param entryId 条目 id
     * @param sell    true = 玩家卖给市场
     * @param units   份数
     */
    public static boolean trade(ServerPlayer player, String entryId, boolean sell, int units) {
        if (player == null || player.isRemoved() || units <= 0) {
            return false;
        }
        MinecraftServer server = player.server;
        if (!SuiteConfig.featureEnabled(FeatureKeys.MARKET)) {
            say(player, "playersuite.market.msg.featureOff");
            return false;
        }
        if (sell && !SuiteConfig.marketSellEnabled()) {
            say(player, "playersuite.market.msg.sellOff");
            return false;
        }
        if (!sell && !SuiteConfig.marketBuyEnabled()) {
            say(player, "playersuite.market.msg.buyOff");
            return false;
        }
        MarketCatalog.Entry entry = MarketCatalog.byId(entryId);
        if (entry == null) {
            say(player, "playersuite.market.msg.stale");
            reopen(player);
            return false;
        }
        if (units > MAX_UNITS) {
            units = MAX_UNITS;
        }
        int unit = Math.max(1, entry.unit());
        long totalItems = (long) units * unit;
        if (totalItems > MAX_ITEMS_PER_TRADE) {
            units = Math.max(1, MAX_ITEMS_PER_TRADE / unit);
            totalItems = (long) units * unit;
        }
        // 防连点：同一玩家同一 tick 只结算一笔
        int tick = server.getTickCount();
        synchronized (LAST_TRADE_TICK) {
            Integer last = LAST_TRADE_TICK.get(player.getUUID());
            if (last != null && last == tick) {
                say(player, "playersuite.market.msg.tooFast");
                return false;
            }
            if (LAST_TRADE_TICK.size() > 4096) {
                LAST_TRADE_TICK.clear();
            }
            LAST_TRADE_TICK.put(player.getUUID(), tick);
        }

        MarketState state = MarketState.of(server);
        long now = MarketState.dayTime(server);
        MarketState.EntryState entryState = state.stateFor(entry, now, MarketState.restockTicks() > 0L);
        if (entryState == null || entryState.disabled()) {
            say(player, "playersuite.market.msg.disabled");
            return false;
        }
        return sell
                ? doSell(player, state, entry, entryState, units, (int) totalItems)
                : doBuy(player, server, state, entry, entryState, units, (int) totalItems);
    }

    /** 买入：先扣钱 + 扣库存，再发货；发货不完整则整单回滚（退钱 + 退库存 + 退限购额度）。 */
    private static boolean doBuy(ServerPlayer player, MinecraftServer server, MarketState state,
                                MarketCatalog.Entry entry, MarketState.EntryState entryState,
                                int units, int items) {
        long price = state.buyPrice(entry, entryState);
        if (price <= 0L) {
            say(player, "playersuite.market.msg.notSelling");
            return false;
        }
        if (price > MarketCatalog.PRICE_CAP / Math.max(1, units)) {
            say(player, "playersuite.market.msg.priceOverflow");
            return false;
        }
        long cost = price * units;

        // 每日限购（自然日，日界 = 06:00，见 MarketState.DAY_TICKS）
        int dailyLimit = SuiteConfig.marketDailyBuyLimit();
        long day = MarketState.dayIndex(server);
        boolean dailyUsed = false;
        if (dailyLimit > 0) {
            int already = state.boughtToday(player.getUUID(), day);
            if (already + items > dailyLimit) {
                say(player, "playersuite.market.msg.dailyLimit", Math.max(0, dailyLimit - already));
                return false;
            }
        }

        if (!state.takeStock(entry, entryState, units)) {
            say(player, "playersuite.market.msg.noStock", Math.max(0, entryState.stock()));
            return false;
        }
        if (dailyLimit > 0) {
            state.addBought(player.getUUID(), day, items);
            dailyUsed = true;
        }
        if (!Economy.withdraw(player, cost, "market_buy")) {
            state.restoreStock(entry, entryState, units);
            if (dailyUsed) {
                state.subtractBought(player.getUUID(), day, items);
            }
            say(player, "playersuite.market.msg.noMoney", Economy.label(cost));
            return false;
        }

        ItemStack sample = new ItemStack(entry.item(), 1);
        int inserted = insert(player, sample, items);
        if (inserted < items) {
            // 背包放不下：整单回滚
            takeItems(player, sample, inserted);
            Economy.deposit(player, cost, "market_refund");
            state.restoreStock(entry, entryState, units);
            if (dailyUsed) {
                state.subtractBought(player.getUUID(), day, items);
            }
            say(player, "playersuite.market.msg.inventoryFull");
            return false;
        }
        player.getInventory().setChanged();
        playSound(player, SoundEvents.ITEM_PICKUP);
        say(player, "playersuite.market.msg.bought", label(sample), units, items, Economy.label(cost));
        PlayerSuiteMod.LOGGER.debug("[market] {} 买入 {} ×{} 份（{} 件），花费 {}",
                player.getGameProfile().getName(), entry.id(), units, items, cost);
        reopen(player);
        return true;
    }

    /** 卖出：先在背包里数够再精确扣除（同物品同组件），然后付钱。 */
    private static boolean doSell(ServerPlayer player, MarketState state, MarketCatalog.Entry entry,
                                 MarketState.EntryState entryState, int units, int items) {
        long price = state.sellPrice(entry, entryState);
        if (price <= 0L) {
            say(player, "playersuite.market.msg.notBuying");
            return false;
        }
        ItemStack sample = new ItemStack(entry.item(), 1);
        int have = countItems(player, sample);
        if (have < items) {
            say(player, "playersuite.market.msg.notEnough", items, have);
            return false;
        }
        if (price > MarketCatalog.PRICE_CAP / Math.max(1, units)) {
            say(player, "playersuite.market.msg.priceOverflow");
            return false;
        }
        int removed = takeItems(player, sample, items);
        if (removed < items) {
            // 理论上到不了这里（上面数过）；保底把已扣的塞回去，绝不吞物品
            insert(player, sample, removed);
            say(player, "playersuite.market.msg.notEnough", items, have);
            return false;
        }
        long income = price * units;
        Economy.deposit(player, income, "market_sell");
        player.getInventory().setChanged();
        playSound(player, SoundEvents.EXPERIENCE_ORB_PICKUP);
        say(player, "playersuite.market.msg.sold", label(sample), units, items, Economy.label(income));
        PlayerSuiteMod.LOGGER.debug("[market] {} 卖出 {} ×{} 份（{} 件），收入 {}",
                player.getGameProfile().getName(), entry.id(), units, items, income);
        reopen(player);
        return true;
    }

    // ---------------------------------------------------------------- 文本输入

    /** {@code InputRouter} 接线入口（feature = market）。客户端输入完全不可信，全部重校验。 */
    public static void onTextInput(ServerPlayer player, String action, String text) {
        if (player == null || player.isRemoved() || action == null || action.isEmpty()) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.MARKET)) {
            say(player, "playersuite.market.msg.featureOff");
            return;
        }
        if (!(player.containerMenu instanceof MarketMenu menu) || menu.viewerPlayer() != player) {
            // 市场界面没打开：忽略，避免被当成通用输入通道
            return;
        }
        String raw = text == null ? "" : text.trim();
        if (raw.length() > 32) {
            raw = raw.substring(0, 32);
        }
        boolean adminAction = action.startsWith(ACTION_PRICE_BUY) || action.startsWith(ACTION_PRICE_SELL)
                || action.startsWith(ACTION_STOCK);
        if (!raw.isEmpty() && raw.charAt(0) == '-') {
            // 只有 OP 管理动作允许 -1（取消覆盖 / 不限库存）
            if (!adminAction || !"-1".equals(raw)) {
                say(player, "playersuite.market.msg.numberBad");
                return;
            }
        } else if (!raw.chars().allMatch(Character::isDigit)) {
            say(player, "playersuite.market.msg.numberBad");
            return;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            say(player, "playersuite.market.msg.numberBad");
            return;
        }

        if (action.startsWith(ACTION_BUY_QTY) || action.startsWith(ACTION_SELL_QTY)) {
            int row = rowIndex(action);
            if (row < 0) {
                return;
            }
            if (value <= 0L || value > MAX_UNITS) {
                say(player, "playersuite.market.msg.unitsBad", MAX_UNITS);
                return;
            }
            trade(player, menu.rowId(row), action.startsWith(ACTION_SELL_QTY), (int) value);
            return;
        }
        if (action.startsWith(ACTION_PRICE_BUY)) {
            adminSetPrice(player, menu, action, true, value);
            return;
        }
        if (action.startsWith(ACTION_PRICE_SELL)) {
            adminSetPrice(player, menu, action, false, value);
            return;
        }
        if (action.startsWith(ACTION_STOCK)) {
            adminSetStock(player, menu, action, value);
        }
    }

    private static void adminSetPrice(ServerPlayer player, MarketMenu menu, String action, boolean buy, long value) {
        int row = rowIndex(action);
        if (row < 0 || !requireAdmin(player)) {
            return;
        }
        MarketCatalog.Entry entry = MarketCatalog.byId(menu.rowId(row));
        if (entry == null) {
            say(player, "playersuite.market.msg.stale");
            return;
        }
        MarketState state = MarketState.of(player.server);
        MarketState.EntryState es = state.stateFor(entry, MarketState.dayTime(player.server),
                MarketState.restockTicks() > 0L);
        long clamped = value < 0L ? -1L : Math.min(value, MarketCatalog.PRICE_CAP);
        if (buy) {
            state.setBuyOverride(es, clamped);
        } else {
            state.setSellOverride(es, clamped);
        }
        long shown = buy ? state.buyPrice(entry, es) : state.sellPrice(entry, es);
        audit(player, buy ? "playersuite.market.audit.buyPrice" : "playersuite.market.audit.sellPrice",
                player.getGameProfile().getName(), entry.id(), Economy.label(shown));
        reopen(player);
    }

    private static void adminSetStock(ServerPlayer player, MarketMenu menu, String action, long value) {
        int row = rowIndex(action);
        if (row < 0 || !requireAdmin(player)) {
            return;
        }
        MarketCatalog.Entry entry = MarketCatalog.byId(menu.rowId(row));
        if (entry == null) {
            say(player, "playersuite.market.msg.stale");
            return;
        }
        MarketState state = MarketState.of(player.server);
        MarketState.EntryState es = state.stateFor(entry, MarketState.dayTime(player.server),
                MarketState.restockTicks() > 0L);
        // 同 setStockByCommand：只改剩余库存，不改补货基准，否则重开页面时会被配置回满冲掉。
        state.setStock(es, (int) Math.max(-1L, Math.min(999999L, value)));
        audit(player, "playersuite.market.audit.stock", player.getGameProfile().getName(), entry.id(),
                Component.translatable("playersuite.market.stockUnits", Math.max(0, es.stock())));
        reopen(player);
    }

    /**
     * 行内管理按钮：0 改买价 / 1 改卖价 / 2 改库存 由客户端直接开输入框，
     * 服务端在这里只处理 3（补满该条目）与 4（临时禁用 / 启用）。
     */
    public static void manageButton(ServerPlayer player, String entryId, int action) {
        if (action < 3) {
            return;
        }
        if (!requireAdmin(player)) {
            return;
        }
        MarketCatalog.Entry entry = MarketCatalog.byId(entryId);
        if (entry == null) {
            say(player, "playersuite.market.msg.stale");
            return;
        }
        MarketState state = MarketState.of(player.server);
        long now = MarketState.dayTime(player.server);
        MarketState.EntryState es = state.stateFor(entry, now, MarketState.restockTicks() > 0L);
        if (action == 3) {
            state.restock(es, now);
            audit(player, "playersuite.market.audit.restockOne", player.getGameProfile().getName(), entry.id(),
                    es.stock() < 0 ? "∞" : String.valueOf(es.stock()));
        } else {
            boolean disable = !es.disabled();
            state.setDisabled(es, disable);
            audit(player, disable ? "playersuite.market.audit.disable" : "playersuite.market.audit.enable",
                    player.getGameProfile().getName(), entry.id());
        }
        reopen(player);
    }

    /** 管理动作的权限门槛（{@code adminPermission}，默认 3）。 */
    private static boolean requireAdmin(ServerPlayer player) {
        if (!player.hasPermissions(SuiteConfig.adminPermission())) {
            say(player, "playersuite.msg.noPermission", SuiteConfig.adminPermission());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 全局动作

    /** 一键全店补货（adminPermission）。 */
    public static void restockAll(ServerPlayer player) {
        if (!requireAdmin(player)) {
            return;
        }
        MarketState state = MarketState.of(player.server);
        long now = MarketState.dayTime(player.server);
        int count = 0;
        for (MarketCatalog.Entry entry : MarketCatalog.entries()) {
            state.restock(state.stateFor(entry, now, MarketState.restockTicks() > 0L), now);
            count++;
        }
        audit(player, "playersuite.market.audit.restockAll", player.getGameProfile().getName(), count);
        reopen(player);
    }

    /** 界面里的重载目录按钮（adminPermission）。 */
    public static void reloadCatalog(ServerPlayer player) {
        if (!requireAdmin(player)) {
            return;
        }
        int removed = reload(player.server);
        audit(player, "playersuite.market.audit.reload", player.getGameProfile().getName(),
                MarketCatalog.entries().size(), removed);
        UiState state = ui(player);
        openPage(player, 0, Math.min(state.category, Math.max(0, categoryCount() - 1)), 0, state.adminMode);
    }

    /**
     * 重读配置目录并清理失效条目状态。
     *
     * @return 被清理掉的失效条目数
     */
    public static int reload(MinecraftServer server) {
        MarketCatalog.invalidate();
        int total = MarketCatalog.entries().size();
        int removed;
        int restored = 0;
        try {
            MarketState state = MarketState.of(server);
            removed = state.purgeStaleState(MarketCatalog.validIds());
            // 重载目录 = 回到配置价：清掉全部 OP 改价覆盖
            restored = state.clearPriceOverrides();
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("[market] 清理失效条目状态失败：{}", e.toString());
            removed = 0;
        }
        PlayerSuiteMod.LOGGER.info("[market] 目录已重载：{} 条有效，清理失效状态 {} 条，恢复配置价 {} 条",
                total, removed, restored);
        return removed;
    }

    /** 服务器启动后预热：解析目录 + 清失效状态 + 打一条日志。 */
    public static void warmUp(MinecraftServer server) {
        try {
            MarketCatalog.invalidate();
            int size = MarketCatalog.entries().size();
            MarketState state = MarketState.of(server);
            state.purgeStaleState(MarketCatalog.validIds());
            state.setDirty();
            PlayerSuiteMod.LOGGER.info("官方市场已就绪：目录 {} 条，分类 {} 个，限购日界 06:00，补货间隔 {} 小时",
                    size, MarketCatalog.categories().size(), SuiteConfig.marketRestockHours());
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("[market] 启动预热失败：{}", e.toString());
        }
    }

    /** 按当前 UI 状态重开本页（交易 / 管理动作之后刷新数字）。 */
    private static void reopen(ServerPlayer player) {
        UiState state = ui(player);
        openPage(player, state.page, state.category, state.chipWindow, state.adminMode);
    }

    // ---------------------------------------------------------------- 背包工具

    /** 背包（36 格主包）里与样本同类（物品 + 组件完全一致）的总数。 */
    public static int countItems(Player player, ItemStack sample) {
        if (player == null || sample == null || sample.isEmpty()) {
            return 0;
        }
        int sum = 0;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample)) {
                sum = (int) Math.min(Integer.MAX_VALUE, (long) sum + stack.getCount());
            }
        }
        return sum;
    }

    /** 从背包精确扣除 amount 件同类物品，返回实际扣掉的件数。 */
    public static int takeItems(Player player, ItemStack sample, int amount) {
        if (player == null || sample == null || sample.isEmpty() || amount <= 0) {
            return 0;
        }
        int left = amount;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size() && left > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, sample)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        player.getInventory().setChanged();
        return amount - left;
    }

    /** 按最大堆叠拆分后塞进背包，返回实际放入的件数（放不下就返回缺口，由调用方回滚）。 */
    private static int insert(ServerPlayer player, ItemStack sample, int items) {
        if (player == null || sample == null || sample.isEmpty() || items <= 0) {
            return 0;
        }
        InvWrapper inv = new InvWrapper(player.getInventory());
        int max = Math.max(1, sample.getMaxStackSize());
        int remaining = items;
        int placed = 0;
        while (remaining > 0) {
            int chunk = Math.min(max, remaining);
            ItemStack stack = sample.copyWithCount(chunk);
            ItemStack left = ItemHandlerHelper.insertItemStacked(inv, stack, false);
            int got = chunk - left.getCount();
            if (got <= 0) {
                break;
            }
            placed += got;
            remaining -= got;
        }
        return placed;
    }

    // ---------------------------------------------------------------- 小工具

    /** 解析 action 末尾的页内行号（0..{@link MarketMenu#MAX_ROWS}-1）；非法返回 -1。 */
    private static int rowIndex(String action) {
        int idx = action.lastIndexOf('_');
        if (idx < 0 || idx + 1 >= action.length()) {
            return -1;
        }
        try {
            int row = Integer.parseInt(action.substring(idx + 1));
            return row < 0 || row >= MarketMenu.MAX_ROWS ? -1 : row;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void say(ServerPlayer player, String key, Object... args) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    /** OP 动作留痕：系统消息（存档可查）+ 日志。 */
    static void audit(ServerPlayer actor, String key, Object... args) {
        Component message = Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
        actor.createCommandSourceStack().sendSystemMessage(message);
        PlayerSuiteMod.LOGGER.info("[market][audit] {}", message.getString());
    }

    /** 物品显示名（不注册新物品，直接用原物品名）。 */
    static Component label(ItemStack stack) {
        return stack == null || stack.isEmpty() ? Component.literal("-") : stack.getHoverName();
    }

    private static void playSound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound) {
        try {
            player.playSound(sound, 0.6F, 1.0F);
        } catch (RuntimeException ignored) {
            // 音效失败不影响结算
        }
    }

    /** 全部条目 id（指令补全用）。 */
    public static List<String> entryIds() {
        return MarketCatalog.entries().stream().map(MarketCatalog.Entry::id).toList();
    }

    /** 条目是否存在。 */
    public static boolean hasEntry(String id) {
        return MarketCatalog.byId(id) != null;
    }

    /**
     * 指令用：查某条目库存。
     *
     * @return {@code [剩余份数, 补货基准份数]}（-1 = 不限）；条目不存在返回 null
     */
    public static int[] stockOf(MinecraftServer server, String entryId) {
        MarketCatalog.Entry entry = MarketCatalog.byId(entryId);
        if (entry == null || server == null) {
            return null;
        }
        MarketState state = MarketState.of(server);
        MarketState.EntryState es = state.stateFor(entry, MarketState.dayTime(server),
                MarketState.restockTicks() > 0L);
        return new int[]{es.stock(), es.max()};
    }

    /**
     * 指令用：设置某条目剩余库存（同时把补货基准设成同值）。
     *
     * @param value -1 = 不限
     * @return 设置后的剩余份数；条目不存在返回 null
     */
    public static Integer setStockByCommand(MinecraftServer server, String entryId, int value) {
        MarketCatalog.Entry entry = MarketCatalog.byId(entryId);
        if (entry == null || server == null) {
            return null;
        }
        MarketState state = MarketState.of(server);
        MarketState.EntryState es = state.stateFor(entry, MarketState.dayTime(server),
                MarketState.restockTicks() > 0L);
        // 只改「剩余库存」，不动补货基准 max：
        // 如果连 max 一起改，下一次 stateFor() 会认为「配置里的库存变了」而按配置重新回满，
        // 管理员刚设的值会在一条指令之后被抹掉（实测过的真 bug）。
        state.setStock(es, value);
        PlayerSuiteMod.LOGGER.info("[market][command] 库存设置：{} 剩余 {} / 补货基准 {}",
                entryId, es.stock(), es.max());
        return es.stock();
    }
}
