package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 我的商店（「上架即托管」核心页）：每个货架一行 = 一个可写托管槽 + 改价/补库存/下架按钮。
 *
 * <p>槽位背后直接是全局托管仓（{@link ShopStorage}）的槽位视图：
 * <ul>
 *     <li>放物品 → 物品真实进入托管仓，{@link ShopService#reconcileSlot} 新建商品
 *         （单价默认取配置下限，用「改价」调整）；</li>
 *     <li>取出（左键整组 / 右键一半 / shift / 数字键 / Q 丢弃）→ 物品真实离开托管仓
 *         进入玩家手里，同一入口对账修正库存；取空即自动下架。</li>
 * </ul>
 * 所有放取路径最后都收口到对账函数，不存在「凭空生成物品」的通道。
 *
 * <p>公共头之后追加的 int：{@code [rows, flags, listingCount, listedUnits]}，
 * status 位复用为「每页行数 perPage」；随后是 ownerName/shopName（utf）、
 * todayIncome（long）、每行 price（long）+ stock（int）。
 */
public class ShopMenu extends PageMenu {
    // ---- 界面几何（客户端 ShopScreen / ShopBrowseScreen 共用）----
    public static final int PANEL_WIDTH = 250;
    public static final int ROW_H = 24;
    public static final int SLOT_X = Layout.GRID_LEFT;
    public static final int TEXT_X = 30;
    public static final int MAX_ROWS = 9;

    public static int rowTop(int row) {
        return Layout.contentTop() + row * ROW_H;
    }

    public static int slotY(int row) {
        return rowTop(row) + 3;
    }

    public static int inventoryTop(int rows) {
        return Layout.contentTop() + rows * ROW_H + Layout.GAP;
    }

    public static int footerRow1(int rows) {
        return inventoryTop(rows) + 4 * Layout.PITCH + 4;
    }

    public static int footerRow2(int rows) {
        return footerRow1(rows) + 24;
    }

    public static int panelHeight(int rows) {
        // 底部多留 12px 给统计行
        return footerRow2(rows) + Layout.PITCH + 6 + 12;
    }

    // ---- 按钮编号（自定义段从 BTN_CUSTOM 起；行号 = 页内行，最多 9 行）----
    public static final int BTN_BROWSE = BTN_CUSTOM;        // 10：去逛商店
    public static final int BTN_ADD1_BASE = 11;             // 11..19   +1 库存
    public static final int BTN_ADD64_BASE = 20;            // 20..28   +64 库存
    public static final int BTN_UNLIST_BASE = 29;           // 29..37   自己下架
    public static final int BTN_FORCE_BASE = 38;            // 38..46   强制下架（OP）
    public static final int BTN_RETURN_BASE = 47;           // 47..55   退回卖家（ADMIN）
    public static final int BTN_CLEAR = 56;                 // 清空商店（ADMIN）
    private static final int SUB_INTS = 4;

    private final UUID ownerId;
    private final ShopStorage storage;
    private final ServerPlayer ownerPlayer;      // 服务端；客户端为 null
    private final int rows;
    private final int perPage;
    private final boolean selfView;
    private final boolean adminView;
    private final int[] rowSlots;
    private final long[] rowPrice;
    private final int[] rowStock;
    private final String ownerName;
    private final String shopName;
    private final long todayIncome;
    private final int listingCount;
    private final int listedUnits;
    private final List<ShopListingSlot> shopSlots = new ArrayList<>();

    // ------------------------------------------------------------- 构造

    /** 客户端构造。 */
    public ShopMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ShopMenus.SHOP_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        int[] sub = readSubData(buf, SUB_INTS);
        this.rows = Math.max(1, Math.min(MAX_ROWS, sub[0]));
        int flags = sub[1];
        this.selfView = (flags & 1) != 0;
        this.adminView = (flags & 2) != 0;
        this.listingCount = sub[2];
        this.listedUnits = sub[3];
        this.perPage = Math.max(1, status());
        this.ownerName = buf.readUtf();
        this.shopName = buf.readUtf();
        this.todayIncome = buf.readLong();
        this.ownerId = null;
        this.storage = null;
        this.ownerPlayer = null;
        this.rowSlots = new int[rows];
        this.rowPrice = new long[rows];
        this.rowStock = new int[rows];
        for (int i = 0; i < rows; i++) {
            this.rowPrice[i] = buf.readLong();
            this.rowStock[i] = buf.readInt();
            this.rowSlots[i] = page() * perPage + i;
        }
        for (int i = 0; i < rows; i++) {
            addSlot(new ShopListingSlot(new SimpleContainer(1), rowSlots[i], i, null, true));
        }
        addPlayerInventoryAt(inventory, inventoryTop(rows));
    }

    /** 服务端构造（viewer 打开 owner 的商店）。 */
    public ShopMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer owner,
                    int page, int pages, boolean manage, boolean admin, boolean self, int[] rowSlots) {
        super(ShopMenus.SHOP_MENU.get(), containerId, inventory, viewer, page, pages, manage);
        this.ownerId = owner.getUUID();
        this.storage = ShopService.storage(viewer.server);
        this.ownerPlayer = owner;
        this.rowSlots = rowSlots.clone();
        this.rows = rowSlots.length;
        this.selfView = self;
        this.adminView = admin;
        this.perPage = SuiteConfig.shopBrowsePageSize();
        this.ownerName = owner.getGameProfile().getName();
        ShopData data = ShopService.data(owner);
        this.shopName = ShopService.shopNameOr(owner, data);
        this.todayIncome = data.todayIncome();
        this.listingCount = data.listingCount();
        this.listedUnits = data.listedUnits();
        this.rowPrice = new long[rows];
        this.rowStock = new int[rows];
        boolean fixed = false;
        for (int i = 0; i < rows; i++) {
            // 打开页面先自愈：托管有物而无货架/货架库存漂移，立刻对账
            if (storage.count(ShopStorage.key(ownerId, rowSlots[i])) > 0
                    && data.getListing(rowSlots[i]) == null) {
                ShopService.reconcileSlot(owner, rowSlots[i], false);
                fixed = true;
            }
            ShopData.Listing l = data.getListing(rowSlots[i]);
            if (l != null) {
                int real = storage.count(ShopStorage.key(ownerId, rowSlots[i]));
                if (real != l.stock) {
                    l.stock = real;
                    fixed = true;
                }
                rowPrice[i] = l.price;
                rowStock[i] = real;
            }
            addSlot(new ShopListingSlot(new Custody(owner, rowSlots[i]), rowSlots[i], i, owner, false));
        }
        if (fixed) {
            ShopService.touch(owner, data);   // 把修正过的库存写回附件
        }
        addPlayerInventoryAt(inventory, inventoryTop(rows));
    }

    /** 写入打开菜单的全部附加数据（顺序必须与客户端构造函数一致）。 */
    public static void writeTo(RegistryFriendlyByteBuf buf, long balance, int page, int pages, boolean manage,
                               int rows, int perPage, boolean self, boolean admin,
                               String ownerName, String shopName, long income,
                               int listingCount, int listedUnits,
                               long[] rowPrice, int[] rowStock) {
        int flags = (self ? 1 : 0) | (admin ? 2 : 0);
        writeFull(buf, balance, page, pages, manage, perPage,
                rows, flags, listingCount, listedUnits);
        buf.writeUtf(ownerName == null ? "" : ownerName);
        buf.writeUtf(shopName == null ? "" : shopName);
        buf.writeLong(income);
        for (int i = 0; i < rows; i++) {
            buf.writeLong(i < rowPrice.length ? rowPrice[i] : 0L);
            buf.writeInt(i < rowStock.length ? rowStock[i] : 0);
        }
    }

    // ------------------------------------------------------------- 只读访问

    public UUID ownerId() {
        return ownerId;
    }

    public int rows() {
        return rows;
    }

    public int perPage() {
        return perPage;
    }

    public boolean selfView() {
        return selfView;
    }

    public boolean adminView() {
        return adminView && !selfView;
    }

    public boolean manageView() {
        return canManage() && !selfView;
    }

    public int rowSlot(int row) {
        return row >= 0 && row < rowSlots.length ? rowSlots[row] : -1;
    }

    public long rowPrice(int row) {
        return row >= 0 && row < rowPrice.length ? rowPrice[row] : 0L;
    }

    public int rowStock(int row) {
        return row >= 0 && row < rowStock.length ? rowStock[row] : 0;
    }

    public String ownerName() {
        return ownerName;
    }

    public String shopName() {
        return shopName;
    }

    public long todayIncome() {
        return todayIncome;
    }

    public int listingCount() {
        return listingCount;
    }

    public int listedUnits() {
        return listedUnits;
    }

    // ------------------------------------------------------------- 按钮

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (ownerPlayer == null || viewer.isRemoved()) {
            return false;
        }
        if (buttonId == BTN_BROWSE) {
            ShopService.openBrowse(viewer, 0);
            return true;
        }
        ServerPlayer ownerNow = viewer.server.getPlayerList().getPlayer(ownerId);
        if (ownerNow == null) {
            ShopService.say(viewer, "playersuite.shop.msg.ownerOffline");
            return false;
        }
        boolean self = viewer.getUUID().equals(ownerId);
        if (buttonId == BTN_CLEAR) {
            requireAdmin(viewer, () -> ShopService.clearShop(viewer, ownerNow));
            reopen(viewer, page());
            return true;
        }
        int row = buttonId - BTN_ADD1_BASE;
        if (buttonId >= BTN_ADD1_BASE && row < MAX_ROWS) {
            if (self) {
                ShopService.addStock(ownerNow, slotOfRow(row), 1);
            } else {
                ShopService.say(viewer, "playersuite.shop.msg.onlySelf");
            }
            reopen(viewer, page());
            return true;
        }
        if (buttonId >= BTN_ADD64_BASE && buttonId - BTN_ADD64_BASE < MAX_ROWS) {
            row = buttonId - BTN_ADD64_BASE;
            if (self) {
                ShopService.addStock(ownerNow, slotOfRow(row), 64);
            } else {
                ShopService.say(viewer, "playersuite.shop.msg.onlySelf");
            }
            reopen(viewer, page());
            return true;
        }
        if (buttonId >= BTN_UNLIST_BASE && buttonId - BTN_UNLIST_BASE < MAX_ROWS) {
            row = buttonId - BTN_UNLIST_BASE;
            if (self) {
                ShopService.takeDown(ownerNow, slotOfRow(row));
            } else {
                ShopService.say(viewer, "playersuite.shop.msg.onlySelf");
            }
            reopen(viewer, page());
            return true;
        }
        if (buttonId >= BTN_FORCE_BASE && buttonId - BTN_FORCE_BASE < MAX_ROWS) {
            row = buttonId - BTN_FORCE_BASE;
            if (viewer.hasPermissions(SuiteConfig.managePermission())) {
                ShopService.forceUnlist(viewer, ownerId, slotOfRow(row));
            } else {
                ShopService.say(viewer, "playersuite.shop.msg.noPermission", SuiteConfig.managePermission());
            }
            reopen(viewer, page());
            return true;
        }
        if (buttonId >= BTN_RETURN_BASE && buttonId - BTN_RETURN_BASE < MAX_ROWS) {
            row = buttonId - BTN_RETURN_BASE;
            if (viewer.hasPermissions(SuiteConfig.adminPermission())) {
                ShopService.returnToSeller(viewer, ownerId, slotOfRow(row));
            } else {
                ShopService.say(viewer, "playersuite.shop.msg.noPermission", SuiteConfig.adminPermission());
            }
            reopen(viewer, page());
            return true;
        }
        return false;
    }

    private void requireAdmin(ServerPlayer viewer, Runnable action) {
        if (viewer.hasPermissions(SuiteConfig.adminPermission())) {
            action.run();
        } else {
            ShopService.say(viewer, "playersuite.shop.msg.noPermission", SuiteConfig.adminPermission());
        }
    }

    /** 页内行号 -> 真实货架号（越界返回 -1，由服务层再次拒绝）。 */
    private int slotOfRow(int row) {
        if (row < 0 || row >= rowSlots.length) {
            return -1;
        }
        int max = SuiteConfig.shopListings();
        int slot = rowSlots[row];
        return slot >= 0 && slot < max ? slot : -1;
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        ServerPlayer ownerNow = viewer.server.getPlayerList().getPlayer(ownerId);
        if (ownerNow != null) {
            ShopService.open(viewer, ownerNow, page);
        } else {
            ShopService.openBrowse(viewer, page);
        }
    }

    // ------------------------------------------------------------- 搬运

    /** 背包 ↔ 货架的 shift 搬运（双向都走托管仓，无复制通道）。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (viewer == null || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        if (index < rows) {
            // 货架 -> 背包（= 下架退物；数量不足部分留在货架）
            ShopListingSlot slot = shopSlots.get(index);
            if (!slot.mayPickup(player)) {
                return ItemStack.EMPTY;
            }
            ItemStack all = slot.getItem().copy();
            if (all.isEmpty()) {
                return ItemStack.EMPTY;
            }
            InvWrapper inv = new InvWrapper(player.getInventory());
            int left = ItemHandlerHelper.insertItemStacked(inv, all.copy(), true).getCount();
            int moved = all.getCount() - left;
            if (moved <= 0) {
                ShopService.say((ServerPlayer) player, "playersuite.shop.msg.inventoryFull");
                return ItemStack.EMPTY;
            }
            ItemStack extracted = slot.container.removeItem(0, moved);
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inv, extracted, false);
            if (!leftover.isEmpty()) {
                ItemStack cur = slot.container.getItem(0);
                if (cur.isEmpty()) {
                    slot.container.setItem(0, leftover);
                } else {
                    cur.grow(leftover.getCount());
                    slot.setChanged();
                }
            }
            return ItemStack.EMPTY;
        }
        // 背包 -> 第一个空货架（= 新建商品）
        Slot source = slots.get(index);
        ItemStack stack = source.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ShopListingSlot target = null;
        for (ShopListingSlot s : shopSlots) {
            if (s.mayPlace(stack)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            ShopService.say((ServerPlayer) player, "playersuite.shop.msg.noEmptyShelf");
            return ItemStack.EMPTY;
        }
        int n = Math.min(stack.getCount(), stack.getMaxStackSize());
        if (n <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack moved = stack.split(n);
        if (moved.isEmpty()) {
            return ItemStack.EMPTY;
        }
        target.set(moved);
        source.setChanged();
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------- 内部类

    /** 托管仓的槽位视图：一切读写直接落到 {@link ShopStorage}。 */
    private final class Custody implements Container {
        private final ServerPlayer owner;
        private final int slot;
        private final String key;

        Custody(ServerPlayer owner, int slot) {
            this.owner = owner;
            this.slot = slot;
            this.key = ShopStorage.key(owner.getUUID(), slot);
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return storage.count(key) <= 0;
        }

        @Override
        public ItemStack getItem(int i) {
            return storage.getLive(key);
        }

        @Override
        public ItemStack removeItem(int i, int amount) {
            ItemStack live = storage.getLive(key);
            if (live.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int take = Math.min(Math.max(0, amount), live.getCount());
            if (take <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack out = live.split(take);
            if (live.isEmpty()) {
                storage.remove(key);
            } else {
                storage.commit();
            }
            ShopService.reconcileSlot(owner, slot, true);
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            ItemStack live = storage.getLive(key);
            if (live.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack out = live.copy();
            storage.remove(key);
            return out;
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                storage.remove(key);
            } else {
                storage.put(key, stack);
            }
            ShopService.reconcileSlot(owner, slot, true);
        }

        @Override
        public void setChanged() {
            ShopService.reconcileSlot(owner, slot, true);
        }

        @Override
        public int getMaxStackSize() {
            // 托管仓一格可以超过 64（补库存后），展示与放取都以真实数量为准
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            storage.remove(key);
            ShopService.reconcileSlot(owner, slot, true);
        }
    }

    /**
     * 可写托管槽。客户端实例（readOnly）永远拒绝交互：
     * 不做任何本地预测，界面内容完全由服务端容器同步驱动，杜绝幽灵物品。
     */
    public final class ShopListingSlot extends Slot {
        private final int listingSlot;
        private final boolean readOnly;
        private final ServerPlayer owner;

        ShopListingSlot(Container container, int listingSlot, int row, ServerPlayer owner, boolean readOnly) {
            super(container, 0, SLOT_X, slotY(row));
            this.listingSlot = listingSlot;
            this.readOnly = readOnly;
            this.owner = owner;
            shopSlots.add(this);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (readOnly || stack == null || stack.isEmpty() || owner == null) {
                return false;
            }
            // 只允许店主本人向空货架放物品（OP 不能替别人上架）
            if (viewer == null || viewer != owner) {
                return false;
            }
            return storage != null && storage.count(ShopStorage.key(ownerId(), listingSlot)) <= 0;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !readOnly && player != null && player.getUUID().equals(ownerId);
        }

        @Override
        public int getMaxStackSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public void onTake(Player player, ItemStack taken) {
            super.onTake(player, taken);
            if (!readOnly && owner != null) {
                ShopService.reconcileSlot(owner, listingSlot, true);
            }
        }

        /** 该槽对应的真实货架号（客户端按钮回调用）。 */
        public int listingSlot() {
            return listingSlot;
        }
    }

    /** 当前页某行的展示物品（服务端/客户端都从容器同步的槽位读）。 */
    public ItemStack rowDisplay(int row) {
        int index = row;
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(index).getItem();
    }
}
