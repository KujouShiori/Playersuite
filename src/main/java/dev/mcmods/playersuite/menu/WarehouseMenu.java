package dev.mcmods.playersuite.menu;

import dev.mcmods.playersuite.config.WarehouseConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.registry.ModMenus;
import dev.mcmods.playersuite.warehouse.WarehouseData;
import dev.mcmods.playersuite.warehouse.WarehouseService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * 个人仓库容器菜单。
 *
 * <p>槽位布局：
 * <pre>
 *   [0, warehouseSlots)                  本页可见的仓库槽位（背后是玩家的仓库数据附件）
 *   [warehouseSlots, warehouseSlots+27)  玩家主物品栏
 *   [warehouseSlots+27, +36)             玩家快捷栏
 * </pre>
 *
 * <p>等级 / 容量 / 余额 / 价格通过 {@link ContainerData} 服务端单向同步，
 * 因此界面上的价格与余额会随着取放物品实时刷新（ITEM 货币模式下余额就是背包里的货币物品数量）。
 */
public class WarehouseMenu extends AbstractContainerMenu {
    /** 界面按钮 id（由 {@link #clickMenuButton(Player, int)} 处理）。 */
    public static final int BTN_UPGRADE = 0;
    public static final int BTN_SORT = 1;
    public static final int BTN_PREV_PAGE = 2;
    public static final int BTN_NEXT_PAGE = 3;
    /** 返回功能总入口（与其它页面一致）。 */
    public static final int BTN_BACK_TO_HUB = 4;

    /** ContainerData 下标。 */
    public static final int DATA_LEVEL = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_BALANCE = 2;
    public static final int DATA_PRICE = 3;
    private static final int DATA_COUNT = 4;

    private static final int PLAYER_SLOTS = 36;

    /** 客户端侧缓存，由 {@link Sync#set(int, int)} 写入；服务端优先读真实数据。 */
    private int clientLevel;
    private int clientCapacity;
    private int clientBalance;
    private int clientPrice;

    private final ContainerData sync = new Sync();
    private final ItemStackHandler storage;

    /** 服务端专用，客户端为 null。 */
    private final ServerPlayer viewer;
    private final ServerPlayer owner;
    private final WarehouseData data;

    private final int capacity;
    private final int rows;
    private final int rowsPerPage;
    private final int rowsOnPage;
    private final int page;
    private final int pages;
    private final int startIndex;
    private final int warehouseSlots;
    private final boolean canManage;

    // ------------------------------------------------------------- 构造

    /** 客户端构造：从服务端 openMenu 的附加数据里还原布局。 */
    public WarehouseMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, null, null, null,
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readInt(),
                extraData.readBoolean());
    }

    /** 服务端构造。 */
    public WarehouseMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer owner,
                         WarehouseData data, int page) {
        this(containerId, inventory, viewer, owner, data,
                data.capacity(),
                data.rows(),
                page,
                WarehouseService.pageCount(data.rows()),
                WarehouseConfig.rowsPerPage(),
                data.getLevel(),
                Economy.intForGui(Economy.balance(viewer)),
                data.nextPrice(),
                WarehouseService.canManage(viewer, owner));
    }

    private WarehouseMenu(int containerId, Inventory inventory,
                          ServerPlayer viewer, ServerPlayer owner, WarehouseData data,
                          int capacity, int rows, int page, int pages, int rowsPerPage, int level,
                          int balance, int price, boolean canManage) {
        super(ModMenus.WAREHOUSE_MENU.get(), containerId);
        this.viewer = viewer;
        this.owner = owner;
        this.data = data;
        this.capacity = Math.max(9, capacity);
        this.rows = Math.max(1, rows);
        this.rowsPerPage = Math.max(1, rowsPerPage);
        this.pages = Math.max(1, pages);
        this.page = Math.max(0, Math.min(page, this.pages - 1));
        this.startIndex = this.page * this.rowsPerPage * 9;
        this.rowsOnPage = Math.max(1, Math.min(this.rowsPerPage, this.rows - this.startIndex / 9));
        this.warehouseSlots = this.rowsOnPage * 9;
        this.canManage = canManage;
        // 客户端只需要一个能容纳同名下标的空仓库，真实内容由容器同步填充
        this.storage = data != null ? data.handler() : new ItemStackHandler(this.capacity);

        for (int i = 0; i < this.warehouseSlots; i++) {
            addSlot(new SlotItemHandler(this.storage, this.startIndex + i,
                    WarehouseLayout.slotX(i % 9),
                    WarehouseLayout.warehouseSlotY(i / 9)));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + col + row * 9,
                        WarehouseLayout.slotX(col),
                        WarehouseLayout.playerTop(this.rowsOnPage) + row * WarehouseLayout.PITCH));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col,
                    WarehouseLayout.slotX(col),
                    WarehouseLayout.hotbarTop(this.rowsOnPage)));
        }

        this.clientLevel = level;
        this.clientCapacity = this.capacity;
        this.clientBalance = balance;
        this.clientPrice = price;
        addDataSlots(sync);
    }

    /**
     * 服务端单向同步四个展示值。原版 {@code broadcastChanges()} 会自动 diff 并下发变化，
     * 因此这里只需提供取值与写入逻辑。
     */
    private final class Sync implements ContainerData {
        @Override
        public int getCount() {
            return DATA_COUNT;
        }

        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_LEVEL -> level();
                case DATA_CAPACITY -> capacityValue();
                case DATA_BALANCE -> balance();
                case DATA_PRICE -> price();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case DATA_LEVEL -> clientLevel = value;
                case DATA_CAPACITY -> clientCapacity = value;
                case DATA_BALANCE -> clientBalance = value;
                case DATA_PRICE -> clientPrice = value;
                default -> {
                    // 未知下标忽略
                }
            }
        }
    }

    // ------------------------------------------------------------- 同步

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved() && (owner == null || !owner.isRemoved());
    }

    // ------------------------------------------------------------- 按钮

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (viewer == null || owner == null || data == null) {
            return false;
        }
        switch (buttonId) {
            case BTN_UPGRADE -> {
                if (!canManage) {
                    viewer.displayClientMessage(Component.translatable("playersuite.msg.upgrade.noPermission"), true);
                    return true;
                }
                WarehouseService.upgrade(viewer, owner, page);
                return true;
            }
            case BTN_SORT -> {
                WarehouseService.sort(viewer, owner);
                return true;
            }
            case BTN_PREV_PAGE -> {
                if (page > 0) {
                    WarehouseService.open(viewer, owner, page - 1);
                }
                return true;
            }
            case BTN_NEXT_PAGE -> {
                if (page < pages - 1) {
                    WarehouseService.open(viewer, owner, page + 1);
                }
                return true;
            }
            case BTN_BACK_TO_HUB -> {
                dev.mcmods.playersuite.ui.FeatureOpeners.open(viewer,
                        dev.mcmods.playersuite.ui.FeatureOpeners.HUB, 0);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------- Shift 移动

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot source = slots.get(index);
        ItemStack carried = source.getItem();
        if (carried.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = carried.copy();
        boolean fromWarehouse = index < warehouseSlots;
        int from = fromWarehouse ? warehouseSlots : 0;
        int to = fromWarehouse ? warehouseSlots + PLAYER_SLOTS : warehouseSlots;
        transfer(remaining, from, to, !fromWarehouse);
        if (remaining.getCount() == carried.getCount()) {
            // 一点都没移动，按原版约定返回原堆栈
            return carried;
        }
        source.set(remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        return ItemStack.EMPTY;
    }

    /**
     * 把 {@code stack} 里的物品尽可能搬进 {@code [from, to)} 范围的槽位：
     * 先与同类物品合并，再放入空格。{@code fromLast} 决定是否反向遍历（原版箱子行为）。
     */
    private void transfer(ItemStack stack, int from, int to, boolean fromLast) {
        if (stack.isStackable()) {
            for (int step = 0; step < to - from && !stack.isEmpty(); step++) {
                Slot slot = slots.get(fromLast ? to - 1 - step : from + step);
                ItemStack target = slot.getItem();
                if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) {
                    continue;
                }
                int limit = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
                int space = limit - target.getCount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, stack.getCount());
                ItemStack merged = target.copy();
                merged.grow(move);
                slot.set(merged);
                stack.shrink(move);
            }
        }
        for (int step = 0; step < to - from && !stack.isEmpty(); step++) {
            Slot slot = slots.get(fromLast ? to - 1 - step : from + step);
            if (!slot.getItem().isEmpty()) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
            ItemStack put = stack.split(Math.min(limit, stack.getCount()));
            if (!put.isEmpty()) {
                slot.set(put);
            }
        }
    }

    // ------------------------------------------------------------- 只读访问

    public int level() {
        return data != null ? data.getLevel() : clientLevel;
    }

    public int capacityValue() {
        return data != null ? data.capacity() : clientCapacity;
    }

    public int balance() {
        return viewer != null ? Economy.intForGui(Economy.balance(viewer)) : clientBalance;
    }

    /** 下一次扩充的价格；满级为 -1。 */
    public int price() {
        return data != null ? data.nextPrice() : clientPrice;
    }

    public int rowsOnPage() {
        return rowsOnPage;
    }

    public int rows() {
        return rows;
    }

    public int page() {
        return page;
    }

    public int pages() {
        return pages;
    }

    public boolean canManage() {
        return canManage;
    }

    public int warehouseSlots() {
        return warehouseSlots;
    }

    /** 仓库主人；客户端为 null。 */
    public ServerPlayer owner() {
        return owner;
    }

    /** 本页第一个格子背后的真实仓库下标（调试用）。 */
    public int startIndex() {
        return startIndex;
    }
}
