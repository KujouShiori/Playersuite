package dev.mcmods.playersuite.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家商店的全局托管仓（SavedData：{@code playersuite_shop_storage}）。
 *
 * <p>「上架即托管」：玩家放进自己货架的物品真实存在这里，
 * 购买从这里发货，下架/清店从这里退回，与卖家是否在线无关。
 *
 * <p>结构：
 * <ul>
 *     <li>{@code items}：键 -> 托管物品。键形如 {@code 卖家uuid_槽号}；
 *         管理员退回但卖家不在线时暂存为 {@code 卖家uuid_ret_序号}，等其上线领取；</li>
 *     <li>{@code byOwner}：卖家 -> 其正式托管键的索引（由 items 重建）；</li>
 *     <li>读取旧档/坏档一律容错，物品解析失败的条目直接跳过（物品仍留在 NBT 里不会被凭空复制）。</li>
 * </ul>
 *
 * <p>注意：{@link #getLive(String)} 返回的是存储中的【活引用】，供托管槽位容器
 * 直接 split/shrink；调用方改完数量必须 {@link #commit()} 或重新 {@code put}。
 */
public final class ShopStorage extends SavedData {
    public static final String NAME = "playersuite_shop_storage";
    private static final String TAG_ITEMS = "Items";
    private static final String TAG_KEY = "Key";
    private static final String TAG_ITEM = "Item";
    private static final String RET_MARK = "_ret_";

    public static final SavedData.Factory<ShopStorage> FACTORY =
            new SavedData.Factory<>(ShopStorage::new, ShopStorage::load);

    private final Map<String, ItemStack> items = new HashMap<>();
    /** 卖家 -> 正式托管键（不含 ret 键）。每次读 items 现算，避免第二份状态漂移。 */
    private Map<UUID, List<String>> ownerIndex;

    // ------------------------------------------------------------------ 键

    public static String key(UUID seller, int slot) {
        return seller + "_" + slot;
    }

    /** 解析正式托管键里的槽号；不是本格式返回 -1。 */
    public static int slotOfKey(String key) {
        int idx = key.lastIndexOf('_');
        if (idx <= 0 || idx + 1 >= key.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析正式托管键里的卖家 uuid；失败返回 null。 */
    public static UUID sellerOfKey(String key) {
        int idx = key.lastIndexOf('_');
        if (idx <= 0) {
            return null;
        }
        try {
            return UUID.fromString(key.substring(0, idx));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isReturnKey(String key) {
        int idx = key.lastIndexOf('_');
        return idx > 0 && key.lastIndexOf(RET_MARK) > 0
                && idx + 1 < key.length() && isDigits(key, idx + 1);
    }

    private static boolean isDigits(String s, int from) {
        if (from >= s.length()) {
            return false;
        }
        for (int i = from; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String returnKey(UUID seller, int seq) {
        return seller + RET_MARK + seq;
    }

    // ------------------------------------------------------------------ 访问

    public static ShopStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 存储中的活引用（可能直接就是 map 里的对象）；不要修改数量后忘记 commit/setDirty。 */
    public ItemStack getLive(String key) {
        ItemStack stack = items.get(key);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** 只读副本。 */
    public ItemStack copy(String key) {
        ItemStack stack = items.get(key);
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public int count(String key) {
        ItemStack stack = items.get(key);
        return stack == null ? 0 : stack.getCount();
    }

    /** 放入/替换托管物品；空堆等价于删除。总是保存副本，避免外部继续持有。 */
    public void put(String key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            if (items.remove(key) != null) {
                ownerIndex = null;
                setDirty();
            }
            return;
        }
        items.put(key, stack.copy());
        ownerIndex = null;
        setDirty();
    }

    /** 删除并返回托管物品（副本）。 */
    public ItemStack remove(String key) {
        ItemStack removed = items.remove(key);
        if (removed != null) {
            ownerIndex = null;
            setDirty();
        }
        return removed == null ? ItemStack.EMPTY : removed;
    }

    /** 活引用数量发生变化后调用：清索引缓存并落脏。 */
    public void commit() {
        ownerIndex = null;
        setDirty();
    }

    /** 某卖家当前全部正式托管键（副本）。 */
    public List<String> keysOf(UUID seller) {
        ensureIndex();
        List<String> list = ownerIndex.get(seller);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private void ensureIndex() {
        if (ownerIndex == null) {
            Map<UUID, List<String>> index = new HashMap<>();
            for (String key : items.keySet()) {
                if (isReturnKey(key)) {
                    continue;
                }
                UUID seller = sellerOfKey(key);
                if (seller != null) {
                    index.computeIfAbsent(seller, k -> new ArrayList<>()).add(key);
                }
            }
            ownerIndex = index;
        }
    }

    // ------------------------------------------------------------------ 待退回

    /** 管理员操作退回但卖家不在线：物品暂存，等其上线领取。 */
    public void addReturn(UUID seller, ItemStack stack) {
        if (seller == null || stack == null || stack.isEmpty()) {
            return;
        }
        int seq = 0;
        while (items.containsKey(returnKey(seller, seq))) {
            seq++;
        }
        items.put(returnKey(seller, seq), stack.copy());
        setDirty();
    }

    public boolean hasReturns(UUID seller) {
        String prefix = seller + RET_MARK;
        for (String key : items.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 取走某卖家全部待退回物品（上线时调用）。 */
    public List<ItemStack> takeReturns(UUID seller) {
        List<ItemStack> out = new ArrayList<>();
        String prefix = seller + RET_MARK;
        List<String> matched = new ArrayList<>();
        for (String key : items.keySet()) {
            if (key.startsWith(prefix)) {
                matched.add(key);
            }
        }
        for (String key : matched) {
            ItemStack stack = items.remove(key);
            if (stack != null && !stack.isEmpty()) {
                out.add(stack);
            }
        }
        if (!matched.isEmpty()) {
            setDirty();
        }
        return out;
    }

    // ------------------------------------------------------------------ 落盘

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, ItemStack> e : items.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            CompoundTag one = new CompoundTag();
            one.putString(TAG_KEY, e.getKey());
            Tag item = e.getValue().save(registries);
            if (item instanceof CompoundTag ct) {
                one.put(TAG_ITEM, ct);
            }
            list.add(one);
        }
        tag.put(TAG_ITEMS, list);
        return tag;
    }

    private static ShopStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopStorage storage = new ShopStorage();
        if (tag.contains(TAG_ITEMS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                String key = one.getString(TAG_KEY);
                if (key.isEmpty() || !one.contains(TAG_ITEM, Tag.TAG_COMPOUND)) {
                    continue;
                }
                ItemStack stack = ItemStack.parseOptional(registries, one.getCompound(TAG_ITEM));
                if (!stack.isEmpty()) {
                    storage.items.put(key, stack);
                }
            }
        }
        return storage;
    }
}
