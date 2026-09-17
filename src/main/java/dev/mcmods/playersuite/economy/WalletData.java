package dev.mcmods.playersuite.economy;

import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家金币钱包数据：余额 + 最近的收支流水。
 *
 * <p>存在数据附件 {@code playersuite:wallet} 上，跟随 playerdata 持久化，死亡不掉。
 * 余额使用 long，上限由配置 {@code [economy].maxBalance} 控制。
 */
public final class WalletData {
    private static final String TAG_BALANCE = "Balance";
    private static final String TAG_LOG = "Log";
    private static final String TAG_TIME = "Time";
    private static final String TAG_DELTA = "Delta";
    private static final String TAG_AFTER = "After";
    private static final String TAG_REASON = "Reason";

    /** 一笔流水：时间戳、变动额、变动后余额、原因（如「银行取息」「商店售出」）。 */
    public record Txn(long time, long delta, long after, String reason) {
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_TIME, time);
            tag.putLong(TAG_DELTA, delta);
            tag.putLong(TAG_AFTER, after);
            tag.putString(TAG_REASON, reason == null ? "" : reason);
            return tag;
        }

        static Txn fromTag(CompoundTag tag) {
            return new Txn(tag.getLong(TAG_TIME), tag.getLong(TAG_DELTA), tag.getLong(TAG_AFTER),
                    tag.getString(TAG_REASON));
        }
    }

    public static final IAttachmentSerializer<CompoundTag, WalletData> SERIALIZER = new Serializer();

    private long balance;
    private final List<Txn> log = new ArrayList<>();

    public WalletData() {
        this(0L);
    }

    public WalletData(long balance) {
        this.balance = Math.max(0L, balance);
    }

    public long balance() {
        return balance;
    }

    /** 最近的流水在前。 */
    public List<Txn> log() {
        return List.copyOf(log);
    }

    /** 加钱（按上限裁剪），返回实际增加的数额。 */
    public long add(long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        long cap = SuiteConfig.maxBalance();
        long target = Math.min(cap, balance + Math.min(amount, cap));
        long applied = Math.max(0L, target - balance);
        balance = target;
        return applied;
    }

    /** 扣钱；余额不足返回 false 且不做任何变动。 */
    public boolean take(long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (balance < amount) {
            return false;
        }
        balance -= amount;
        return true;
    }

    public void set(long amount) {
        balance = Math.max(0L, Math.min(SuiteConfig.maxBalance(), amount));
    }

    /** 记录一笔流水（超出配置条数自动丢弃最旧的）。 */
    public void record(long delta, String reason) {
        int max = SuiteConfig.transactionLogSize();
        if (!SuiteConfig.logTransactions() || max <= 0 || delta == 0L) {
            return;
        }
        log.add(0, new Txn(System.currentTimeMillis(), delta, balance, reason == null ? "" : reason));
        while (log.size() > max) {
            log.remove(log.size() - 1);
        }
    }

    /** 空钱包（不落盘，保持存档干净）。 */
    public boolean isBlank() {
        return balance <= 0L && log.isEmpty();
    }

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, WalletData> {
        @Override
        public WalletData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            WalletData data = new WalletData(Math.max(0L, tag.getLong(TAG_BALANCE)));
            if (tag.contains(TAG_LOG, Tag.TAG_LIST)) {
                ListTag list = tag.getList(TAG_LOG, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    data.log.add(Txn.fromTag(list.getCompound(i)));
                }
            }
            return data;
        }

        @Override
        public CompoundTag write(WalletData data, HolderLookup.Provider provider) {
            // 返回 null 表示不必写入存档（附件会被移除）
            if (data.isBlank()) {
                return null;
            }
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_BALANCE, data.balance);
            int max = Math.max(1, SuiteConfig.transactionLogSize());
            ListTag list = new ListTag();
            for (int i = 0; i < Math.min(max, data.log.size()); i++) {
                list.add(data.log.get(i).toTag());
            }
            if (!list.isEmpty()) {
                tag.put(TAG_LOG, list);
            }
            return tag;
        }
    }
}
