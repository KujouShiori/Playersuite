package dev.mcmods.playersuite.mail;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一封邮件的数据体（存放在玩家附件 {@link MailData} 里）。
 *
 * <p>字段：发件人（UUID + 名字，公告邮件 UUID 为 null、名字为空串）、标题、正文、
 * 时间戳（毫秒）、已读/已领取标记、附件（{@link ItemStackHandler}，可为空）。
 *
 * <p>序列化用 CompoundTag（见 {@link #writeTo(CompoundTag, HolderLookup.Provider)} /
 * {@link #readFrom(CompoundTag, HolderLookup.Provider)}），读旧档时缺键一律取默认值。
 */
public final class MailEntry {

    private static final String TAG_ID = "Id";
    private static final String TAG_SENDER = "Sender";
    private static final String TAG_SENDER_NAME = "SenderName";
    private static final String TAG_TITLE = "Title";
    private static final String TAG_BODY = "Body";
    private static final String TAG_TIME = "Time";
    private static final String TAG_READ = "Read";
    private static final String TAG_CLAIMED = "Claimed";
    private static final String TAG_ATT = "Att";

    private final long id;
    /** 公告邮件为 null。 */
    private final UUID senderId;
    /** 公告邮件为空串，界面显示「系统」。 */
    private final String senderName;
    private final String title;
    private final String body;
    private final long time;
    private boolean read;
    private boolean claimed;
    /** 没有附件时为 null（省存档）。 */
    private ItemStackHandler attachments;

    public MailEntry(long id, UUID senderId, String senderName, String title, String body,
                     long time, List<ItemStack> attachments, boolean claimed) {
        this.id = id;
        this.senderId = senderId;
        this.senderName = senderName == null ? "" : senderName;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.time = time;
        this.read = false;
        this.claimed = claimed;
        setAttachmentList(attachments);
    }

    // ------------------------------------------------------------------ 读取

    public long id() {
        return id;
    }

    /** 发件人 UUID；公告邮件为 null。 */
    public UUID senderId() {
        return senderId;
    }

    public String senderName() {
        return senderName;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public long time() {
        return time;
    }

    public boolean isSystem() {
        return senderId == null;
    }

    public boolean isRead() {
        return read;
    }

    public boolean isClaimed() {
        return claimed;
    }

    public void markRead() {
        this.read = true;
    }

    public void markClaimed() {
        this.claimed = true;
    }

    /** 非空附件列表（副本）。已领取时返回空列表。 */
    public List<ItemStack> attachmentList() {
        List<ItemStack> out = new ArrayList<>();
        if (attachments != null && !claimed) {
            for (int i = 0; i < attachments.getSlots(); i++) {
                ItemStack stack = attachments.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    out.add(stack.copy());
                }
            }
        }
        return out;
    }

    /** 用于列表行图标的附件（含已领取的，仅作展示）；越界或无附件返回 EMPTY。 */
    public ItemStack attachmentForDisplay(int index) {
        if (attachments == null || index < 0 || index >= attachments.getSlots()) {
            return ItemStack.EMPTY;
        }
        return attachments.getStackInSlot(index);
    }

    public int attachmentCount() {
        return attachments == null ? 0 : attachments.getSlots();
    }

    private void setAttachmentList(List<ItemStack> stacks) {
        List<ItemStack> filled = new ArrayList<>();
        if (stacks != null) {
            for (ItemStack stack : stacks) {
                if (stack != null && !stack.isEmpty()) {
                    filled.add(stack.copy());
                }
            }
        }
        if (filled.isEmpty()) {
            this.attachments = null;
            return;
        }
        this.attachments = new ItemStackHandler(filled.size());
        for (int i = 0; i < filled.size(); i++) {
            this.attachments.setStackInSlot(i, filled.get(i));
        }
    }

    // ------------------------------------------------------------------ 序列化

    public CompoundTag writeTo(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong(TAG_ID, id);
        if (senderId != null) {
            tag.putUUID(TAG_SENDER, senderId);
        }
        tag.putString(TAG_SENDER_NAME, senderName);
        tag.putString(TAG_TITLE, title);
        tag.putString(TAG_BODY, body);
        tag.putLong(TAG_TIME, time);
        tag.putBoolean(TAG_READ, read);
        tag.putBoolean(TAG_CLAIMED, claimed);
        if (attachments != null) {
            tag.put(TAG_ATT, attachments.serializeNBT(provider));
        }
        return tag;
    }

    public static MailEntry readFrom(CompoundTag tag, HolderLookup.Provider provider) {
        UUID sender = tag.hasUUID(TAG_SENDER) ? tag.getUUID(TAG_SENDER) : null;
        MailEntry entry = new MailEntry(
                tag.getLong(TAG_ID),
                sender,
                readString(tag, TAG_SENDER_NAME),
                readString(tag, TAG_TITLE),
                readString(tag, TAG_BODY),
                tag.getLong(TAG_TIME),
                null,
                tag.getBoolean(TAG_CLAIMED));
        entry.read = tag.getBoolean(TAG_READ);
        if (tag.contains(TAG_ATT, Tag.TAG_COMPOUND)) {
            ItemStackHandler handler = new ItemStackHandler(1);
            try {
                handler.deserializeNBT(provider, tag.getCompound(TAG_ATT));
                entry.attachments = handler;
            } catch (RuntimeException ignored) {
                // 附件数据损坏时保留邮件本体，附件按空处理
            }
        }
        return entry;
    }

    private static String readString(CompoundTag tag, String key) {
        try {
            return tag.getString(key);
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
