package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 玩家商店的附件数据：店名 + 自己的货架（快照），随 playerdata 持久化。
 *
 * <p>注意：货架里的【真实物品】不在这里，而在服务端全局托管仓 {@link ShopStorage}
 * （上架即托管）。本类保存的是每个货架的展示快照、单价、库存与上架时间，
 * 以及店名与「今日收入」统计。任何一端与托管仓不一致时，以托管仓为准，
 * 由 {@link ShopService#reconcileSlot} 修正。
 *
 * <p>读旧档必须容错：所有键都按「可能不存在」处理。
 */
public final class ShopData {
    private static final String TAG_NAME = "Name";
    private static final String TAG_LISTINGS = "Listings";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_PRICE = "Price";
    private static final String TAG_STOCK = "Stock";
    private static final String TAG_TIME = "Time";
    private static final String TAG_ITEM = "Item";
    private static final String TAG_INCOME_DAY = "IncomeDay";
    private static final String TAG_TODAY_INCOME = "TodayIncome";

    /** 一个货架：物品快照（数量恒为 1，仅作展示模板）+ 单价 + 库存 + 上架时间。 */
    public static final class Listing {
        public ItemStack sample;
        public long price;
        public int stock;
        public long listedAt;

        public Listing(ItemStack sample, long price, int stock, long listedAt) {
            this.sample = sample == null ? ItemStack.EMPTY : sample.copyWithCount(1);
            this.price = price;
            this.stock = Math.max(0, stock);
            this.listedAt = listedAt;
        }
    }

    private String shopName = "";
    /** 货架下标 -> 货架；用 LinkedHashMap 保持槽位顺序。 */
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    /** 今日收入统计所属的自然日（服务器本地时区，epochDay）。 */
    private long incomeDay = Long.MIN_VALUE;
    private long todayIncome;

    // ------------------------------------------------------------------ 店名

    public String shopName() {
        return shopName == null ? "" : shopName;
    }

    /** 设置店名；只做长度截断（按码点），非法字符由 ShopService 在入口清洗。 */
    public void setShopName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int max = SuiteConfig.shopNameMaxLen();
        int count = name.codePointCount(0, name.length());
        if (count > max) {
            int end = 0;
            for (int seen = 0; seen < max && end < name.length(); seen++) {
                end = name.offsetByCodePoints(end, 1);
            }
            name = name.substring(0, end);
        }
        this.shopName = name;
    }

    // ------------------------------------------------------------------ 货架

    public Listing getListing(int slot) {
        return listings.get(slot);
    }

    public void putListing(int slot, Listing listing) {
        listings.put(slot, listing);
    }

    public Listing removeListing(int slot) {
        return listings.remove(slot);
    }

    public void clearListings() {
        listings.clear();
    }

    public Map<Integer, Listing> listings() {
        return listings;
    }

    /** 按槽位升序返回占用中的 (槽位, 货架)。 */
    public List<Map.Entry<Integer, Listing>> sortedListings() {
        List<Map.Entry<Integer, Listing>> out = new ArrayList<>(listings.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    public int listingCount() {
        return listings.size();
    }

    public int listedUnits() {
        int sum = 0;
        for (Listing l : listings.values()) {
            sum = Math.min(Integer.MAX_VALUE, sum + l.stock);
        }
        return sum;
    }

    /** 该玩家是否已占用了配置的全部货架。 */
    public boolean full() {
        return listings.size() >= SuiteConfig.shopListings();
    }

    // ------------------------------------------------------------------ 收入统计

    /** 服务器本地时区的今天（epochDay）。跨天时把今日收入清零。 */
    public void rollDay() {
        long today = java.time.LocalDate.now(TimeZone.getDefault().toZoneId()).toEpochDay();
        if (incomeDay != today) {
            incomeDay = today;
            todayIncome = 0L;
        }
    }

    /** 记一笔今日收入（防溢出，按 long 上限饱和）。 */
    public void addTodayIncome(long amount) {
        if (amount <= 0L) {
            return;
        }
        rollDay();
        if (Long.MAX_VALUE - todayIncome < amount) {
            todayIncome = Long.MAX_VALUE;
        } else {
            todayIncome += amount;
        }
    }

    public long todayIncome() {
        rollDay();
        return todayIncome;
    }

    public boolean isEmptyEverything() {
        return shopName.isEmpty() && listings.isEmpty() && todayIncome <= 0L;
    }

    // ------------------------------------------------------------------ 序列化

    /** 附件序列化器：与仓库同款写法。 */
    public static final IAttachmentSerializer<CompoundTag, ShopData> SERIALIZER =
            new IAttachmentSerializer<CompoundTag, ShopData>() {
                @Override
                public ShopData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider registries) {
                    ShopData data = new ShopData();
                    data.readFrom(tag, registries);
                    return data;
                }

                @Override
                public CompoundTag write(ShopData data, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    data.writeTo(tag, registries);
                    return tag.isEmpty() ? null : tag;   // 返回 null 表示不落盘
                }
            };

    public void writeTo(CompoundTag tag, HolderLookup.Provider registries) {
        if (!shopName.isEmpty()) {
            tag.putString(TAG_NAME, shopName);
        }
        if (!listings.isEmpty()) {
            ListTag list = new ListTag();
            for (Map.Entry<Integer, Listing> e : sortedListings()) {
                Listing l = e.getValue();
                CompoundTag one = new CompoundTag();
                one.putInt(TAG_SLOT, e.getKey());
                one.putLong(TAG_PRICE, l.price);
                one.putInt(TAG_STOCK, l.stock);
                one.putLong(TAG_TIME, l.listedAt);
                if (!l.sample.isEmpty()) {
                    Tag item = l.sample.save(registries);
                    if (item instanceof CompoundTag ct) {
                        one.put(TAG_ITEM, ct);
                    }
                }
                list.add(one);
            }
            tag.put(TAG_LISTINGS, list);
        }
        if (incomeDay != Long.MIN_VALUE || todayIncome > 0L) {
            tag.putLong(TAG_INCOME_DAY, incomeDay);
            tag.putLong(TAG_TODAY_INCOME, todayIncome);
        }
    }

    public void readFrom(CompoundTag tag, HolderLookup.Provider registries) {
        listings.clear();
        shopName = tag.getString(TAG_NAME);   // 缺键得到 ""，天然容错
        if (tag.contains(TAG_LISTINGS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_LISTINGS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                // 旧档/坏档一律按默认值处理，绝不假设键存在
                int slot = one.getInt(TAG_SLOT);
                if (slot < 0 || slot >= 27) {
                    continue;
                }
                ItemStack sample = one.contains(TAG_ITEM, Tag.TAG_COMPOUND)
                        ? ItemStack.parseOptional(registries, one.getCompound(TAG_ITEM))
                        : ItemStack.EMPTY;
                if (sample.isEmpty()) {
                    continue; // 没有物品快照的货架直接丢弃（托管端会兜底）
                }
                listings.put(slot, new Listing(sample,
                        one.getLong(TAG_PRICE), one.getInt(TAG_STOCK), one.getLong(TAG_TIME)));
            }
        }
        incomeDay = tag.contains(TAG_INCOME_DAY) ? tag.getLong(TAG_INCOME_DAY) : Long.MIN_VALUE;
        todayIncome = Math.max(0L, tag.getLong(TAG_TODAY_INCOME));
    }
}
