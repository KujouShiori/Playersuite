package dev.mcmods.playersuite.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 全服在架商品浏览索引（SavedData：{@code playersuite_shop_browse}）。
 *
 * <p>离线玩家的商店也要能被浏览与购买，因此「目录」不放在玩家附件里，
 * 而是全局一份。每行记录 = 卖家 + 槽号 + 展示快照 + 单价 + 库存 + 上架时间。
 *
 * <p>排序规则固定为「上架时间 -> 卖家 uuid -> 槽号」，保证分页稳定；
 * 购买/下架引起的行号漂移由服务端在点击时重新解析（行号只是提示，最终按 key 校验）。
 */
public final class ShopCatalog extends SavedData {
    public static final String NAME = "playersuite_shop_browse";
    private static final String TAG_ENTRIES = "Entries";
    private static final String TAG_SELLER = "Seller";
    private static final String TAG_SELLER_NAME = "SellerName";
    private static final String TAG_SHOP_NAME = "ShopName";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_PRICE = "Price";
    private static final String TAG_STOCK = "Stock";
    private static final String TAG_TIME = "Time";
    private static final String TAG_ITEM = "Item";

    public static final SavedData.Factory<ShopCatalog> FACTORY =
            new SavedData.Factory<>(ShopCatalog::new, ShopCatalog::load);

    /** 按「上架时间 → 卖家 → 槽号」稳定排序（同一毫秒内也要顺序确定）。 */
    private static final Comparator<Entry> ORDER =
            Comparator.comparingLong((Entry e) -> e.time)
                    .thenComparing((Entry e) -> e.seller)
                    .thenComparingInt((Entry e) -> e.slot);

    /** 一条在架记录（库存/单价可变的轻量载体）。 */
    public static final class Entry {
        public final UUID seller;
        public String sellerName;
        public String shopName;
        public final int slot;
        public ItemStack sample;
        public long price;
        public int stock;
        public final long time;

        public Entry(UUID seller, String sellerName, String shopName, int slot,
                     ItemStack sample, long price, int stock, long time) {
            this.seller = seller;
            this.sellerName = sellerName == null ? "" : sellerName;
            this.shopName = shopName == null ? "" : shopName;
            this.slot = slot;
            this.sample = sample == null ? ItemStack.EMPTY : sample.copyWithCount(1);
            this.price = price;
            this.stock = stock;
            this.time = time;
        }

        public String storageKey() {
            return ShopStorage.key(seller, slot);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    // ------------------------------------------------------------------ 访问

    public static ShopCatalog of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 排序后的全部在架记录（副本列表，元素本身可变）。 */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>(entries);
        out.sort(ORDER);
        return out;
    }

    public int total() {
        return entries.size();
    }

    /** 第 globalRow 行（按 {@link #list()} 的顺序）；越界返回 null。 */
    public Entry row(int globalRow) {
        if (globalRow < 0) {
            return null;
        }
        List<Entry> sorted = list();
        return globalRow < sorted.size() ? sorted.get(globalRow) : null;
    }

    public Entry find(UUID seller, int slot) {
        for (Entry e : entries) {
            if (e.slot == slot && e.seller.equals(seller)) {
                return e;
            }
        }
        return null;
    }

    public List<Entry> entriesOf(UUID seller) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seller.equals(seller)) {
                out.add(e);
            }
        }
        out.sort(ORDER);
        return out;
    }

    /** 新增或覆盖一条记录（按卖家+槽号去重）。 */
    public void upsert(Entry entry) {
        remove(entry.seller, entry.slot);
        entries.add(entry);
        setDirty();
    }

    /** 删除一条记录，返回是否存在过。 */
    public boolean remove(UUID seller, int slot) {
        boolean changed = entries.removeIf(e -> e.seller.equals(seller) && e.slot == slot);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public void clearOf(UUID seller) {
        if (entries.removeIf(e -> e.seller.equals(seller))) {
            setDirty();
        }
    }

    /** 更新价格/库存/名称等易变字段并落脏。 */
    public void touch(Entry entry) {
        // 元素与 entries 里的是同一对象，这里只负责置脏
        if (entry != null) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------ 落盘

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : list()) {
            if (e.sample.isEmpty()) {
                continue;
            }
            CompoundTag one = new CompoundTag();
            one.putUUID(TAG_SELLER, e.seller);
            one.putString(TAG_SELLER_NAME, e.sellerName);
            one.putString(TAG_SHOP_NAME, e.shopName);
            one.putInt(TAG_SLOT, e.slot);
            one.putLong(TAG_PRICE, e.price);
            one.putInt(TAG_STOCK, e.stock);
            one.putLong(TAG_TIME, e.time);
            Tag item = e.sample.save(registries);
            if (item instanceof CompoundTag ct) {
                one.put(TAG_ITEM, ct);
            }
            list.add(one);
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    private static ShopCatalog load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopCatalog catalog = new ShopCatalog();
        if (tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.hasUUID(TAG_SELLER)) {
                    continue;
                }
                ItemStack sample = one.contains(TAG_ITEM, Tag.TAG_COMPOUND)
                        ? ItemStack.parseOptional(registries, one.getCompound(TAG_ITEM))
                        : ItemStack.EMPTY;
                if (sample.isEmpty()) {
                    continue;
                }
                catalog.entries.add(new Entry(one.getUUID(TAG_SELLER),
                        one.getString(TAG_SELLER_NAME), one.getString(TAG_SHOP_NAME),
                        one.getInt(TAG_SLOT), sample,
                        one.getLong(TAG_PRICE), one.getInt(TAG_STOCK), one.getLong(TAG_TIME)));
            }
        }
        return catalog;
    }
}
