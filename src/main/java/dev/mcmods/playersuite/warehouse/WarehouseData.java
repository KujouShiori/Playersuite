package dev.mcmods.playersuite.warehouse;

import dev.mcmods.playersuite.config.WarehouseConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 玩家个人仓库的数据体：等级 + 物品堆栈。
 *
 * <p>格子数量由等级推导，但真实容量以 {@link ItemStackHandler#getSlots()} 为准：
 * 这样即使管理员把 {@code maxRows} 调小，也不会凭空删除玩家已经存放的物品。
 */
public final class WarehouseData {
    private static final String TAG_LEVEL = "Level";
    private static final String TAG_ITEMS = "Items";

    /** 排序规则：注册名 -> 数量多在前 -> 堆叠上限大在前。 */
    private static final Comparator<ItemStack> SORT_ORDER = (a, b) -> {
        int cmp = registryName(a).compareTo(registryName(b));
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(b.getCount(), a.getCount());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(b.getMaxStackSize(), a.getMaxStackSize());
    };

    private int level;
    private ItemStackHandler items;

    public WarehouseData(int level, int capacity) {
        this.level = Math.max(0, level);
        this.items = new ItemStackHandler(Math.max(9, capacity));
    }

    /** 附件默认实例：0 级 + 配置的初始容量。 */
    public static WarehouseData createDefault() {
        return new WarehouseData(0, WarehouseConfig.initialCapacity());
    }

    // ------------------------------------------------------------------ 读写

    public int getLevel() {
        return level;
    }

    /** 实际格子数（可能被配置变更影响过，因此以 handler 为准）。 */
    public int capacity() {
        return items.getSlots();
    }

    /** 实际行数。 */
    public int rows() {
        return Math.max(1, capacity() / 9);
    }

    public ItemStackHandler handler() {
        return items;
    }

    public boolean isMaxLevel() {
        return WarehouseConfig.isMaxLevel(level);
    }

    /** 下一次扩充的价格，满级返回 -1。 */
    public int nextPrice() {
        return WarehouseConfig.priceForLevel(level);
    }

    public int nextLevelCapacity() {
        return WarehouseConfig.capacityForLevel(level + 1);
    }

    public boolean isEmpty() {
        for (int i = 0; i < items.getSlots(); i++) {
            if (!items.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int usedSlots() {
        int count = 0;
        for (int i = 0; i < items.getSlots(); i++) {
            if (!items.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 变更

    /**
     * 等级 +1 并扩容，返回新的容量。
     */
    public int upgradeOneLevel() {
        setLevel(level + 1);
        return capacity();
    }

    /**
     * 设置等级并按需扩容（只增不减，避免丢失物品）。
     */
    public void setLevel(int newLevel) {
        this.level = Math.max(0, newLevel);
        resizeTo(WarehouseConfig.capacityForLevel(this.level));
    }

    /**
     * 把容量增长到至少 {@code targetCapacity}（向下取整到 9 的倍数，至少 9 格）。
     */
    public void resizeTo(int targetCapacity) {
        int target = Math.max(9, targetCapacity - (targetCapacity % 9));
        if (target <= items.getSlots()) {
            return;
        }
        ItemStackHandler bigger = new ItemStackHandler(target);
        for (int i = 0; i < items.getSlots(); i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) {
                bigger.setStackInSlot(i, stack);
            }
        }
        this.items = bigger;
    }

    /**
     * 配置变更后的自愈：保证容量不小于等级应有的容量。
     */
    public void validate() {
        int expected = WarehouseConfig.capacityForLevel(level);
        if (capacity() < expected) {
            resizeTo(expected);
        }
    }

    /**
     * 一键整理：先按规则排序，再紧凑堆叠到前面的格子。
     */
    public void sort() {
        List<ItemStack> collected = new ArrayList<>();
        int size = items.getSlots();
        for (int i = 0; i < size; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) {
                collected.add(stack.copy());
                items.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        collected.sort(SORT_ORDER);
        ItemStackHandler out = new ItemStackHandler(size);
        for (ItemStack stack : collected) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(out, stack, false);
            if (!rest.isEmpty()) {
                // 理论上不会发生（容量不变且允许堆叠），保底放第一个空格
                for (int i = 0; i < out.getSlots(); i++) {
                    if (out.getStackInSlot(i).isEmpty()) {
                        out.setStackInSlot(i, rest);
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < size; i++) {
            items.setStackInSlot(i, out.getStackInSlot(i));
        }
    }

    public void clearAll() {
        for (int i = 0; i < items.getSlots(); i++) {
            items.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    private static String registryName(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    // ------------------------------------------------------------------ 序列化

    public static final IAttachmentSerializer<CompoundTag, WarehouseData> SERIALIZER = new Serializer();

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, WarehouseData> {
        @Override
        public WarehouseData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            int level = Math.max(0, tag.getInt(TAG_LEVEL));
            WarehouseData data = new WarehouseData(level, WarehouseConfig.capacityForLevel(level));
            if (tag.contains(TAG_ITEMS, Tag.TAG_COMPOUND)) {
                // ItemStackHandler 会自行按存储的 Size 调整容量（不会截断玩家物品）
                data.items.deserializeNBT(provider, tag.getCompound(TAG_ITEMS));
            }
            return data;
        }

        @Override
        public CompoundTag write(WarehouseData data, HolderLookup.Provider provider) {
            // 返回 null 表示本次无需写入（附件会被从存档中移除）
            if (data.level == 0 && data.isEmpty()) {
                return null; // 全新且空的仓库不必落盘
            }
            CompoundTag tag = new CompoundTag();
            tag.putInt(TAG_LEVEL, data.level);
            tag.put(TAG_ITEMS, data.items.serializeNBT(provider));
            return tag;
        }
    }
}
