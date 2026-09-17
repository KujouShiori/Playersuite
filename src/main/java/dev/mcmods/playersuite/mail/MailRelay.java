package dev.mcmods.playersuite.mail;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 邮件中转表（SavedData，主世界数据，文件 {@code data/playersuite_mail_relay.dat}）。
 *
 * <p>收件人最终数据仍然只存在玩家附件 {@link MailData} 里；本表只解决两个
 * “收件人当前不在线、无法直接写附件”的场景：
 * <ol>
 *     <li><b>离线私信</b>：NeoForge 没有公开 API 能修改离线玩家的附件，
 *         所以按 UUID 暂存整封邮件 NBT，收件人登录时（{@link MailEvents}）搬进其收件箱；</li>
 *     <li><b>全服公告日志</b>：管理员发的公告记在这里（只留最近若干条），
 *         在线玩家立投，离线玩家登录时按游标（{@link MailData#lastBroadcastSeq()}）补投。</li>
 * </ol>
 *
 * <p>所有方法只允许在逻辑服务端调用（事件/菜单回调都在服务端线程）。
 */
public final class MailRelay extends SavedData {

    public static final String DATA_NAME = "playersuite_mail_relay";
    /** 单人离线暂存上限（封）。 */
    public static final int PENDING_CAP = 32;
    /** 公告日志保留条数。 */
    public static final int BROADCAST_CAP = 16;

    private static final String TAG_NEXT_SEQ = "NextSeq";
    private static final String TAG_BROADCASTS = "Broadcasts";
    private static final String TAG_PENDING = "Pending";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_MAILS = "Mails";
    private static final String TAG_SEQ = "Seq";
    private static final String TAG_TITLE = "Title";
    private static final String TAG_BODY = "Body";
    private static final String TAG_TIME = "Time";

    /** 一条全服公告。 */
    public record Broadcast(long seq, String title, String body, long time) {
    }

    private long nextSeq = 1L;
    private final List<Broadcast> broadcasts = new ArrayList<>();
    private final Map<UUID, List<CompoundTag>> pending = new HashMap<>();

    public MailRelay() {
    }

    // ------------------------------------------------------------------ 入口

    public static MailRelay of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MailRelay::new, MailRelay::load), DATA_NAME);
    }

    public static MailRelay load(CompoundTag tag, HolderLookup.Provider provider) {
        MailRelay relay = new MailRelay();
        if (tag == null) {
            return relay;
        }
        relay.nextSeq = Math.max(1L, tag.getLong(TAG_NEXT_SEQ));
        if (tag.contains(TAG_BROADCASTS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_BROADCASTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                relay.broadcasts.add(new Broadcast(entry.getLong(TAG_SEQ),
                        entry.getString(TAG_TITLE), entry.getString(TAG_BODY), entry.getLong(TAG_TIME)));
            }
            while (relay.broadcasts.size() > BROADCAST_CAP) {
                relay.broadcasts.remove(0);
            }
        }
        if (tag.contains(TAG_PENDING, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_PENDING, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                String owner = entry.getString(TAG_OWNER);
                UUID uuid = parseUuid(owner);
                if (uuid == null || !entry.contains(TAG_MAILS, Tag.TAG_LIST)) {
                    continue;
                }
                List<CompoundTag> mails = new ArrayList<>();
                ListTag mailList = entry.getList(TAG_MAILS, Tag.TAG_COMPOUND);
                for (int j = 0; j < mailList.size() && j < PENDING_CAP; j++) {
                    mails.add(mailList.getCompound(j));
                }
                if (!mails.isEmpty()) {
                    relay.pending.put(uuid, mails);
                }
            }
        }
        return relay;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong(TAG_NEXT_SEQ, nextSeq);
        ListTag broadcastList = new ListTag();
        for (Broadcast broadcast : broadcasts) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_SEQ, broadcast.seq());
            entry.putString(TAG_TITLE, broadcast.title());
            entry.putString(TAG_BODY, broadcast.body());
            entry.putLong(TAG_TIME, broadcast.time());
            broadcastList.add(entry);
        }
        tag.put(TAG_BROADCASTS, broadcastList);
        ListTag pendingList = new ListTag();
        for (Map.Entry<UUID, List<CompoundTag>> mapEntry : pending.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_OWNER, mapEntry.getKey().toString());
            ListTag mailList = new ListTag();
            for (CompoundTag mailTag : mapEntry.getValue()) {
                mailList.add(mailTag.copy());
            }
            entry.put(TAG_MAILS, mailList);
            pendingList.add(entry);
        }
        tag.put(TAG_PENDING, pendingList);
        return tag;
    }

    // ------------------------------------------------------------------ 公告

    /** 记录一条公告，返回序号（0 表示失败）。 */
    public long addBroadcast(String title, String body, long timeMillis) {
        long seq = nextSeq;
        nextSeq = Math.min(Long.MAX_VALUE - 1L, seq + 1L);
        broadcasts.add(new Broadcast(seq, title, body, timeMillis));
        while (broadcasts.size() > BROADCAST_CAP) {
            broadcasts.remove(0);
        }
        setDirty();
        return seq;
    }

    /** 按序号升序返回公告日志。 */
    public List<Broadcast> broadcastList() {
        return List.copyOf(broadcasts);
    }

    /** 日志里最大的序号（登录投递后把游标推到它，避免重复扫描）。 */
    public long latestSeq() {
        long max = 0L;
        for (Broadcast broadcast : broadcasts) {
            max = Math.max(max, broadcast.seq());
        }
        return max;
    }

    // ------------------------------------------------------------------ 离线私信暂存

    public int pendingCount(UUID owner) {
        List<CompoundTag> list = pending.get(owner);
        return list == null ? 0 : list.size();
    }

    /** 暂存一封给离线玩家的邮件；满了返回 false（发送方应据此拒绝）。 */
    public boolean addPending(UUID owner, CompoundTag mailTag) {
        if (owner == null || mailTag == null) {
            return false;
        }
        List<CompoundTag> list = pending.computeIfAbsent(owner, k -> new ArrayList<>());
        if (list.size() >= PENDING_CAP) {
            return false;
        }
        list.add(mailTag);
        setDirty();
        return true;
    }

    /** 取走（并清除）某个玩家的全部暂存邮件，按时间升序。 */
    public List<CompoundTag> takePending(UUID owner) {
        List<CompoundTag> list = pending.remove(owner);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        setDirty();
        return list;
    }

    /** 收件箱装不下时把取走的邮件原样放回（保持时间顺序，登录投递的回退路径）。 */
    public void returnPending(UUID owner, List<CompoundTag> mails) {
        if (owner == null || mails == null || mails.isEmpty()) {
            return;
        }
        List<CompoundTag> list = pending.computeIfAbsent(owner, k -> new ArrayList<>());
        for (int i = mails.size() - 1; i >= 0; i--) {
            if (list.size() >= PENDING_CAP) {
                break;
            }
            list.add(0, mails.get(i));
        }
        setDirty();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
