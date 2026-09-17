package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.ui.DisplaySlots;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 逛店页：全服在架商品分页浏览 + 购买（买 1 / 买 16 / 买 64）。
 *
 * <p>行内容是打开时的目录快照（物品通过只读 {@link DisplaySlots} 同步），
 * 一切判定（库存、价格、权限、防连点）都在服务端点击时重新解析，
 * 行号只用于定位「目录全局行」，点击后服务端会按当前状态重开本页。
 *
 * <p>按钮编号：{@code BTN_BUY_BASE + 行号 * 3 + 档位}（档位 0/1/2 对应 1/16/64 件），
 * 另有 {@link #BTN_MY_SHOP} 回「我的商店」。
 *
 * <p>公共头之后追加的 int：{@code [rows, totalEntries]}；每行：
 * price(long) + stock(int) + sellerName(utf) + shopName(utf)。
 */
public class ShopBrowseMenu extends PageMenu {
    public static final int BTN_BUY_BASE = BTN_CUSTOM;
    public static final int BTN_MY_SHOP = BTN_CUSTOM + 30;
    /** 行号 × 数量档 中的档位数。 */
    public static final int TIER_COUNT = 3;
    private static final int SUB_INTS = 2;

    private final int rows;
    private final int pageSize;
    private final long[] rowPrice;
    private final int[] rowStock;
    private final String[] rowSeller;
    private final String[] rowShop;
    private final int totalEntries;

    // ------------------------------------------------------------- 构造

    /** 客户端构造。 */
    public ShopBrowseMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ShopMenus.SHOP_BROWSE_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        int[] sub = readSubData(buf, SUB_INTS);
        this.rows = Math.max(0, sub[0]);
        this.totalEntries = sub[1];
        this.pageSize = Math.max(1, status());
        this.rowPrice = new long[rows];
        this.rowStock = new int[rows];
        this.rowSeller = new String[rows];
        this.rowShop = new String[rows];
        for (int i = 0; i < rows; i++) {
            this.rowPrice[i] = buf.readLong();
            this.rowStock[i] = buf.readInt();
            this.rowSeller[i] = buf.readUtf();
            this.rowShop[i] = buf.readUtf();
            addSlot(DisplaySlots.empty(ShopMenu.SLOT_X, ShopMenu.slotY(i)));
        }
    }

    /** 服务端构造。{@code all} 为打开时排序后的全量目录。 */
    public ShopBrowseMenu(int containerId, Inventory inventory, ServerPlayer viewer,
                          List<ShopCatalog.Entry> all, int from, int rows,
                          int page, int pages, int pageSize) {
        super(ShopMenus.SHOP_BROWSE_MENU.get(), containerId, inventory, viewer, page, pages, false);
        this.rows = Math.max(0, rows);
        this.totalEntries = all.size();
        this.pageSize = Math.max(1, pageSize);
        this.rowPrice = new long[this.rows];
        this.rowStock = new int[this.rows];
        this.rowSeller = new String[this.rows];
        this.rowShop = new String[this.rows];
        for (int i = 0; i < this.rows; i++) {
            ShopCatalog.Entry e = all.get(from + i);
            this.rowPrice[i] = e.price;
            this.rowStock[i] = e.stock;
            this.rowSeller[i] = e.sellerName.isEmpty() ? e.seller.toString() : e.sellerName;
            this.rowShop[i] = e.shopName;
            addSlot(DisplaySlots.of(e.sample.copyWithCount(1), ShopMenu.SLOT_X, ShopMenu.slotY(i)));
        }
    }

    /** 写入附加数据（顺序必须与客户端构造函数一致）。 */
    public static void writeTo(RegistryFriendlyByteBuf buf, long balance, int page, int pages, boolean manage,
                               int rows, List<ShopCatalog.Entry> all, int from, int pageSize) {
        writeFull(buf, balance, page, pages, manage, pageSize, rows, all.size());
        for (int i = 0; i < rows; i++) {
            ShopCatalog.Entry e = all.get(from + i);
            buf.writeLong(e.price);
            buf.writeInt(e.stock);
            buf.writeUtf(e.sellerName.isEmpty() ? e.seller.toString() : e.sellerName);
            buf.writeUtf(e.shopName);
        }
    }

    // ------------------------------------------------------------- 只读访问

    public int rows() {
        return rows;
    }

    public int pageSize() {
        return pageSize;
    }

    public int totalEntries() {
        return totalEntries;
    }

    public long rowPrice(int row) {
        return row >= 0 && row < rowPrice.length ? rowPrice[row] : 0L;
    }

    public int rowStock(int row) {
        return row >= 0 && row < rowStock.length ? rowStock[row] : 0;
    }

    public String rowSeller(int row) {
        return row >= 0 && row < rowSeller.length ? rowSeller[row] : "";
    }

    public String rowShop(int row) {
        return row >= 0 && row < rowShop.length ? rowShop[row] : "";
    }

    /** 第 row 行的展示物品（来自只读展示槽，永远拿不出来）。 */
    public ItemStack rowDisplay(int row) {
        if (row < 0 || row >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(row).getItem();
    }

    /** 全局行号（目录排序后）。 */
    public int globalRow(int row) {
        return page() * pageSize + row;
    }

    // ------------------------------------------------------------- 按钮

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (buttonId == BTN_MY_SHOP) {
            ShopService.open(viewer, viewer, 0);
            return true;
        }
        if (buttonId < BTN_BUY_BASE || buttonId >= BTN_BUY_BASE + ShopMenu.MAX_ROWS * TIER_COUNT) {
            return false;
        }
        int rel = buttonId - BTN_BUY_BASE;
        int row = rel / TIER_COUNT;
        int tier = rel % TIER_COUNT;
        if (row < 0 || row >= rows || row >= ShopMenu.MAX_ROWS) {
            return false;
        }
        ShopService.purchase(viewer, globalRow(row), tier);
        // 无论成败都按当前目录重开本页（行号可能因他人交易漂移）
        ShopService.openBrowse(viewer, page());
        return true;
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        ShopService.openBrowse(viewer, page);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 本页没有可交互容器槽（只有只读展示槽）
        return ItemStack.EMPTY;
    }
}
