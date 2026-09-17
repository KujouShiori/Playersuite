package dev.mcmods.playersuite.mail;

import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.List;

/**
 * 玩家邮箱数据：按时间顺序追加的邮件列表 + 自增 id + 发送冷却时间戳 + 公告投递游标。
 *
 * <p>列表下标 0 是最旧的邮件，界面展示时倒序（最新在前）。
 * 容量上限取当前配置的 {@link SuiteConfig#mailMaxMails()}，超限由业务层拒绝。
 * 读旧档（缺键）时全部取默认值，不会抛异常。
 */
public final class MailData {

    private static final String TAG_MAILS = "Mails";
    private static final String TAG_NEXT_ID = "NextId";
    private static final String TAG_LAST_SEND_AT = "LastSendAt";
    private static final String TAG_LAST_BROADCAST_SEQ = "LastBroadcastSeq";

    /** 附件序列化器（返回 null 表示空数据不落盘）。 */
    public static final IAttachmentSerializer<CompoundTag, MailData> SERIALIZER = new Serializer();

    private final List<MailEntry> mails = new java.util.ArrayList<>();
    private long nextId = 1L;
    /** 上一次成功发送的时间戳（毫秒），用于冷却；0 表示从未发过。 */
    private long lastSendAt;
    /** 已投递公告的最大序号（游标），登录时只补发比它新的公告。 */
    private long lastBroadcastSeq;

    public MailData() {
    }

    /** 附件默认实例。 */
    public static MailData createDefault() {
        return new MailData();
    }

    // ------------------------------------------------------------------ 读取

    /** 底层可变列表（下标 0 最旧）。业务层修改后必须 setData 触发脏标记。 */
    public List<MailEntry> mails() {
        return mails;
    }

    public int size() {
        return mails.size();
    }

    public boolean isEmpty() {
        return mails.isEmpty();
    }

    /** 越界返回 null。 */
    public MailEntry get(int index) {
        return index >= 0 && index < mails.size() ? mails.get(index) : null;
    }

    public int unreadCount() {
        int n = 0;
        for (MailEntry entry : mails) {
            if (!entry.isRead()) {
                n++;
            }
        }
        return n;
    }

    public long lastSendAt() {
        return lastSendAt;
    }

    public void setLastSendAt(long timeMillis) {
        this.lastSendAt = Math.max(0L, timeMillis);
    }

    public long lastBroadcastSeq() {
        return lastBroadcastSeq;
    }

    public void advanceBroadcastSeq(long seq) {
        if (seq > lastBroadcastSeq) {
            this.lastBroadcastSeq = seq;
        }
    }

    /** 当前配置下的收件箱容量。 */
    public static int capacity() {
        return SuiteConfig.mailMaxMails();
    }

    /** 还能不能收新邮件。 */
    public boolean canReceive() {
        return mails.size() < capacity();
    }

    // ------------------------------------------------------------------ 变更

    /** 追加一封邮件；容量满返回 false（超限拒绝由业务层提示）。 */
    public boolean addMail(MailEntry entry) {
        if (entry == null || !canReceive()) {
            return false;
        }
        mails.add(entry);
        return true;
    }

    /** 分配一个自增 id（发件时按收件人邮箱分配）。 */
    public long nextMailId() {
        long id = nextId;
        nextId = id >= Long.MAX_VALUE - 1L ? id : id + 1L;
        return id;
    }

    public MailEntry remove(int index) {
        if (index < 0 || index >= mails.size()) {
            return null;
        }
        return mails.remove(index);
    }

    public void clearAll() {
        mails.clear();
    }

    public boolean isBlank() {
        return mails.isEmpty() && lastSendAt <= 0L && lastBroadcastSeq <= 0L;
    }

    // ------------------------------------------------------------------ 序列化

    public CompoundTag writeTo(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (MailEntry entry : mails) {
            list.add(entry.writeTo(new CompoundTag(), provider));
        }
        tag.put(TAG_MAILS, list);
        tag.putLong(TAG_NEXT_ID, nextId);
        tag.putLong(TAG_LAST_SEND_AT, lastSendAt);
        tag.putLong(TAG_LAST_BROADCAST_SEQ, lastBroadcastSeq);
        return tag;
    }

    public void readFrom(CompoundTag tag, HolderLookup.Provider provider) {
        mails.clear();
        nextId = 1L;
        lastSendAt = 0L;
        lastBroadcastSeq = 0L;
        if (tag == null) {
            return;
        }
        if (tag.contains(TAG_NEXT_ID)) {
            nextId = Math.max(1L, tag.getLong(TAG_NEXT_ID));
        }
        lastSendAt = Math.max(0L, tag.getLong(TAG_LAST_SEND_AT));
        lastBroadcastSeq = Math.max(0L, tag.getLong(TAG_LAST_BROADCAST_SEQ));
        if (tag.contains(TAG_MAILS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_MAILS, Tag.TAG_COMPOUND);
            int cap = capacity();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag mailTag = list.getCompound(i);
                MailEntry entry = MailEntry.readFrom(mailTag, provider);
                if (entry.id() >= nextId) {
                    nextId = Math.min(Long.MAX_VALUE - 1L, entry.id() + 1L);
                }
                // 配置调小后旧档超容量的部分仍读进来，只是不再允许新增，避免丢附件
                mails.add(entry);
            }
            if (mails.size() > cap * 4L + 64L) {
                // 防恶意/损坏存档把列表撑爆：只保留最新的
                mails.subList(0, mails.size() - (cap * 4 + 64)).clear();
            }
        }
    }

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, MailData> {
        @Override
        public MailData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            MailData data = new MailData();
            data.readFrom(tag, provider);
            return data;
        }

        @Override
        public CompoundTag write(MailData data, HolderLookup.Provider provider) {
            // 返回 null 表示无需落盘
            if (data.isBlank()) {
                return null;
            }
            CompoundTag tag = new CompoundTag();
            data.writeTo(tag, provider);
            return tag;
        }
    }
}
