package dev.mcmods.playersuite.ui;

import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 各功能页面的公共容器基类。
 *
 * <p>统一负责三件事，子类只需要关心自己那一页的内容：
 * <ol>
 *     <li><b>分页</b>：{@code page/pages} 与上一页/下一页按钮（按钮编号 0/1）；</li>
 *     <li><b>余额与状态同步</b>：通过 {@link ContainerData} 单向把「余额(long，拆两个 int)、
 *         状态值、选中行、附加值」推给客户端，原版 {@code broadcastChanges()} 自动 diff 下发，
 *         因此不需要为每个功能单独写同步包；</li>
 *     <li><b>按钮协议</b>：界面按钮走 {@code handleInventoryButtonClick} ->
 *         {@link #clickMenuButton(Player, int)}，服务端权威执行。</li>
 * </ol>
 *
 * <p>按钮编号约定：0-9 为公共按钮（上一页/下一页/返回总入口），
 * 功能自己的按钮从 {@link #BTN_CUSTOM}（10）开始。
 */
public abstract class PageMenu extends AbstractContainerMenu {
    public static final int BTN_PREV_PAGE = 0;
    public static final int BTN_NEXT_PAGE = 1;
    public static final int BTN_BACK_TO_HUB = 2;
    public static final int BTN_CUSTOM = 10;

    public static final int DATA_BALANCE_LO = 0;
    public static final int DATA_BALANCE_HI = 1;
    public static final int DATA_STATUS = 2;
    public static final int DATA_SELECTED = 3;
    public static final int DATA_EXTRA = 4;
    public static final int DATA_COUNT = 5;

    /** 界面公共头：打开菜单时先写这一段，子类再追加自己的数据。 */
    public record Header(long balance, int page, int pages, boolean manage, int status) {
    }

    /** 逻辑服务端玩家；客户端为 null。 */
    protected final ServerPlayer viewer;
    protected boolean manage;
    protected int page;
    protected int pages;

    private final ContainerData sync = new PageData();
    private long clientBalance;
    private int status;
    private int selected;
    private int extra;

    // ------------------------------------------------------------- 构造

    /** 客户端构造（子类客户端构造函数里调用）。 */
    protected PageMenu(MenuType<?> type, int id, Inventory inventory) {
        super(type, id);
        this.viewer = null;
        this.manage = false;
        this.page = 0;
        this.pages = 1;
        addDataSlots(sync);
    }

    /** 服务端构造。 */
    protected PageMenu(MenuType<?> type, int id, Inventory inventory, ServerPlayer viewer,
                       int page, int pages, boolean manage) {
        super(type, id);
        this.viewer = viewer;
        this.manage = manage;
        this.pages = Math.max(1, pages);
        this.page = clamp(page);
        addDataSlots(sync);
    }

    // ------------------------------------------------------- 公共头读写

    public static void writeHeader(RegistryFriendlyByteBuf buf, long balance, int page, int pages,
                                   boolean manage, int status) {
        buf.writeLong(balance);
        buf.writeInt(page);
        buf.writeInt(pages);
        buf.writeBoolean(manage);
        buf.writeInt(status);
    }

    public static Header readHeader(RegistryFriendlyByteBuf buf) {
        return new Header(buf.readLong(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readInt());
    }

    /** 客户端构造函数里调用，应用公共头。 */
    protected void applyHeader(Header header) {
        this.clientBalance = Math.max(0L, header.balance());
        this.pages = Math.max(1, header.pages());
        this.page = clamp(header.page());
        this.manage = header.manage();
        this.status = header.status();
    }

    /** 把公共头与子类自己的数据一起写进 openMenu 的附加缓冲区。 */
    protected static void writeFull(RegistryFriendlyByteBuf buf, long balance, int page, int pages,
                                    boolean manage, int status, int... subData) {
        writeHeader(buf, balance, page, pages, manage, status);
        for (int value : subData) {
            buf.writeInt(value);
        }
    }

    protected static int[] readSubData(RegistryFriendlyByteBuf buf, int count) {
        int[] out = new int[Math.max(0, count)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readInt();
        }
        return out;
    }

    // ------------------------------------------------------------- 槽位

    /** 在内容区下方摆放玩家物品栏（27 + 9）。 */
    protected void addPlayerInventory(Inventory inventory, int contentRows) {
        addPlayerInventoryAt(inventory, Layout.inventoryTop(contentRows));
    }

    /** 在指定 y 摆放玩家物品栏（总入口页这种自定义布局用）。 */
    protected void addPlayerInventoryAt(Inventory inventory, int top) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, Layout.GRID_LEFT + col * Layout.PITCH, top + row * Layout.PITCH));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, Layout.GRID_LEFT + col * Layout.PITCH, top + 3 * Layout.PITCH));
        }
    }

    /**
     * 默认禁用.shift-点击搬运（避免功能页面误刷物品）；
     * 需要搬运能力的页面（如仓库）自己重写本方法。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // ------------------------------------------------------------- 按钮

    @Override
    public final boolean clickMenuButton(Player player, int buttonId) {
        if (viewer == null || player != viewer) {
            return false;
        }
        switch (buttonId) {
            case BTN_PREV_PAGE -> {
                if (page > 0) {
                    reopen(viewer, page - 1);
                }
                return true;
            }
            case BTN_NEXT_PAGE -> {
                if (page < pages - 1) {
                    reopen(viewer, page + 1);
                }
                return true;
            }
            case BTN_BACK_TO_HUB -> {
                FeatureOpeners.open(viewer, FeatureOpeners.HUB, 0);
                return true;
            }
            default -> {
                return onAction(viewer, buttonId);
            }
        }
    }

    /** 功能自定义按钮的服务端处理。返回是否执行成功（用于播放音效/提示）。 */
    protected abstract boolean onAction(ServerPlayer viewer, int buttonId);

    /** 以新的页码重新打开本页（数据量变化时也需要重建容器）。 */
    public abstract void reopen(ServerPlayer viewer, int page);

    // ------------------------------------------------------------- 数值

    /** 余额：服务端实时读取，客户端使用同步过来的缓存。 */
    public long balance() {
        return viewer != null ? Math.max(0L, Economy.balance(viewer)) : clientBalance;
    }

    /** 供界面显示的状态值（各功能自定义含义，例如未读邮件数、库存等）。 */
    public int status() {
        return status;
    }

    protected void setStatus(int value) {
        this.status = value;
    }

    public int selected() {
        return selected;
    }

    protected void setSelected(int value) {
        this.selected = value;
    }

    public int extra() {
        return extra;
    }

    protected void setExtra(int value) {
        this.extra = value;
    }

    public boolean canManage() {
        return manage;
    }

    public int page() {
        return page;
    }

    public int pages() {
        return pages;
    }

    /** 当前显示的服务端余额（写公共头时用）。 */
    protected long balanceForSync() {
        return viewer == null ? clientBalance : Math.max(0L, Economy.balance(viewer));
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(value, Math.max(0, pages) - 1));
    }

    /** ContainerData 实现：服务端提供实时值，客户端只接收缓存。 */
    private final class PageData implements ContainerData {
        @Override
        public int getCount() {
            return DATA_COUNT;
        }

        @Override
        public int get(int index) {
            long money = balance();
            return switch (index) {
                case DATA_BALANCE_LO -> (int) (money & 0xFFFFFFFFL);
                case DATA_BALANCE_HI -> (int) (money >>> 32);
                case DATA_STATUS -> status;
                case DATA_SELECTED -> selected;
                case DATA_EXTRA -> extra;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            long money;
            switch (index) {
                case DATA_BALANCE_LO -> {
                    money = (clientBalance & 0xFFFFFFFF_00000000L) | (value & 0xFFFFFFFFL);
                    clientBalance = money;
                }
                case DATA_BALANCE_HI -> {
                    money = (clientBalance & 0xFFFFFFFFL) | ((long) value << 32);
                    clientBalance = money;
                }
                case DATA_STATUS -> status = value;
                case DATA_SELECTED -> selected = value;
                case DATA_EXTRA -> extra = value;
                default -> {
                    // 未知下标忽略
                }
            }
        }
    }
}
