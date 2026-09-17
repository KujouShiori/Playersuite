package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家称号数据：已拥有的称号 id 集合 + 当前佩戴的称号 id（可为空）。
 *
 * <p>称号目录本身在配置文件里，这里只存「谁拥有什么、谁戴着什么」。
 * 读旧档全部按缺省值容错；写档时按当前配置剔除目录里已不存在的脏 id
 * （称号被管理员从配置删除后，旧档里的拥有记录自动清理，正在佩戴的同时卸下）。
 */
public final class TitleData {

    private static final String TAG_OWNED = "Owned";
    private static final String TAG_EQUIPPED = "Equipped";

    /** 附件序列化器（空数据返回 null 表示不落盘）。 */
    public static final IAttachmentSerializer<CompoundTag, TitleData> SERIALIZER = new Serializer();

    private final Set<String> owned = new LinkedHashSet<>();
    private String equipped;

    public TitleData() {
    }

    /** 附件默认实例。 */
    public static TitleData createDefault() {
        return new TitleData();
    }

    // ------------------------------------------------------------------ 读取

    /** 已拥有的称号 id（迭代顺序 = 获得顺序）。 */
    public Set<String> owned() {
        return owned;
    }

    public int ownedCount() {
        return owned.size();
    }

    public boolean owns(String id) {
        return id != null && owned.contains(id);
    }

    /** 当前佩戴的称号 id；没有佩戴返回 null。 */
    public String equipped() {
        return equipped;
    }

    public boolean isEquipped(String id) {
        return id != null && id.equals(equipped);
    }

    // ------------------------------------------------------------------ 变更

    /** 获得一个称号；已拥有返回 false。 */
    public boolean addOwned(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return owned.add(id);
    }

    /** 撤销一个称号；若撤销的正是佩戴中的，同时卸下。 */
    public boolean removeOwned(String id) {
        if (id == null || !owned.remove(id)) {
            return false;
        }
        if (id.equals(equipped)) {
            equipped = null;
        }
        return true;
    }

    /** 佩戴（调用方负责先校验已拥有）；传 null/空表示卸下。 */
    public void setEquipped(String id) {
        this.equipped = id == null || id.isEmpty() ? null : id;
    }

    public boolean isBlank() {
        return owned.isEmpty() && equipped == null;
    }

    // ------------------------------------------------------------------ 序列化

    public CompoundTag writeTo(CompoundTag tag, HolderLookup.Provider provider) {
        // 写档即清洗：目录里已经不存在的 id 视为脏数据，剔除并记日志。
        List<String> dropped = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String id : owned) {
            if (TitleCatalog.knows(id)) {
                kept.add(id);
            } else {
                dropped.add(id);
            }
        }
        for (String id : dropped) {
            owned.remove(id);
            if (id.equals(equipped)) {
                equipped = null;
            }
        }
        if (!dropped.isEmpty() && provider != null) {   // provider 恒非空，这里只是让意图明确
            PlayerSuiteMod.LOGGER.warn("[title] 写档时剔除 {} 个已从配置删除的称号 id：{}",
                    dropped.size(), String.join(",", dropped));
        }
        if (equipped != null && !TitleCatalog.knows(equipped)) {
            PlayerSuiteMod.LOGGER.warn("[title] 写档时佩戴的称号 {} 已不在配置中，自动卸下", equipped);
            equipped = null;
        }
        ListTag list = new ListTag();
        for (String id : owned) {
            list.add(StringTag.valueOf(id));
        }
        tag.put(TAG_OWNED, list);
        if (equipped != null) {
            tag.putString(TAG_EQUIPPED, equipped);
        }
        return tag;
    }

    public void readFrom(CompoundTag tag, HolderLookup.Provider provider) {
        owned.clear();
        equipped = null;
        if (tag == null) {
            return;
        }
        if (tag.contains(TAG_OWNED, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_OWNED, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof StringTag st) {
                    String id = st.getAsString();
                    if (id != null && !id.isEmpty()) {
                        owned.add(id);   // 目录里已删除的 id 先保留，等写档时统一剔除（读的时候不判，避免时序问题）
                    }
                }
            }
        }
        if (tag.contains(TAG_EQUIPPED, Tag.TAG_STRING)) {
            String value = tag.getString(TAG_EQUIPPED);
            if (value != null && !value.isEmpty() && owned.contains(value)) {
                equipped = value;
            }
        }
    }

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, TitleData> {
        @Override
        public TitleData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            TitleData data = new TitleData();
            data.readFrom(tag, provider);
            return data;
        }

        @Override
        public CompoundTag write(TitleData data, HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            data.writeTo(tag, provider);
            // 空数据返回 null：不落盘
            return data.isBlank() ? null : tag;
        }
    }
}
