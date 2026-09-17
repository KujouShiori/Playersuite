package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 银行账户数据（玩家数据附件 {@code playersuite:bank}）。
 *
 * <p>四样东西：本金、上次结息时间戳、流水环形列表、冻结标记。
 * 另外多存一个「上次取款时间戳」用于取款冷却（配置只有秒数，冷却本身要有起点）。
 *
 * <p>所有字段都是 long / boolean，读旧档时缺键一律用默认值，绝不假设键存在。
 * 流水的「余额」列记录的是<b>本金余额</b>（不是钱包余额），这样和银行页面上的
 * 「存款本金」对得上；利息只进钱包、不改本金，所以利息那一行的余额与上一行相同。
 */
public final class BankData {
    private static final String TAG_PRINCIPAL = "Principal";
    private static final String TAG_INTEREST_AT = "LastInterestAt";
    private static final String TAG_WITHDRAW_AT = "LastWithdrawAt";
    private static final String TAG_FROZEN = "Frozen";
    private static final String TAG_HISTORY = "History";
    private static final String TAG_TIME = "Time";
    private static final String TAG_REASON = "Reason";
    private static final String TAG_AMOUNT = "Amount";
    private static final String TAG_BALANCE = "Balance";

    /** 存档里最多保留多少条流水（配置上限 60，这里再留一倍余量，防止配置事故把存档撑大）。 */
    public static final int HARD_CAP = 120;

    /** 一条流水：时间戳(毫秒)、原因短键、金额（+存/入息，-取/手续费）、变动后的本金。 */
    public record Entry(long time, String reason, long amount, long balance) {
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_TIME, time);
            tag.putString(TAG_REASON, reason == null ? "" : reason);
            tag.putLong(TAG_AMOUNT, amount);
            tag.putLong(TAG_BALANCE, balance);
            return tag;
        }

        static Entry fromTag(CompoundTag tag) {
            return new Entry(tag.getLong(TAG_TIME), tag.getString(TAG_REASON),
                    tag.getLong(TAG_AMOUNT), tag.getLong(TAG_BALANCE));
        }
    }

    private long principal;
    private long lastInterestAt;
    private long lastWithdrawAt;
    private boolean frozen;
    private final List<Entry> history = new ArrayList<>();

    /** 附件默认实例（全新玩家：0 本金、未结过息、未冻结）。 */
    public BankData() {
    }

    // ------------------------------------------------------------------ 只读

    public long principal() {
        return principal;
    }

    /** 上次结息时间戳（毫秒）；0 表示从未结过息，利息计时在第一次存取时开始。 */
    public long lastInterestAt() {
        return lastInterestAt;
    }

    /** 上次取款时间戳（毫秒），取款冷却按它判定；0 表示从未取款。 */
    public long lastWithdrawAt() {
        return lastWithdrawAt;
    }

    public boolean frozen() {
        return frozen;
    }

    /** 最近的流水在前（只读视图，要改请用 {@link #addHistory}）。 */
    public List<Entry> history() {
        return Collections.unmodifiableList(history);
    }

    /** 剩余可存额度（本金上限 - 当前本金），永不为负。 */
    public long headroom() {
        long cap = SuiteConfig.bankMaxPrincipal();
        return principal >= cap ? 0L : cap - principal;
    }

    /** 是否需要落盘（全新且空的账户不必写进存档）。 */
    public boolean isBlank() {
        return principal <= 0L && lastInterestAt <= 0L && lastWithdrawAt <= 0L
                && !frozen && history.isEmpty();
    }

    // ------------------------------------------------------------------ 变更

    /** 增加本金（调用前必须已经扣过钱并校验过上限）。 */
    public void addPrincipal(long amount) {
        if (amount <= 0L) {
            return;
        }
        // 防溢出：先与 Long.MAX_VALUE 比较再相加
        if (principal > Long.MAX_VALUE - amount) {
            principal = Long.MAX_VALUE;
            return;
        }
        principal += amount;
    }

    /** 扣减本金；本金不足时返回 false 且不做任何变动。 */
    public boolean takePrincipal(long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (principal < amount) {
            return false;
        }
        principal -= amount;
        return true;
    }

    /** 管理员直接设定本金（负数按 0 处理）。 */
    public void setPrincipal(long value) {
        principal = Math.max(0L, value);
    }

    public void setLastInterestAt(long millis) {
        lastInterestAt = Math.max(0L, millis);
    }

    public void setLastWithdrawAt(long millis) {
        lastWithdrawAt = Math.max(0L, millis);
    }

    public void setFrozen(boolean value) {
        frozen = value;
    }

    /**
     * 追加一条流水（最新的插在最前面），并按配置裁剪成环形列表。
     *
     * @param reason {@code bank_deposit} 这样的短键，界面用 {@code playersuite.bank.history.*} 显示
     */
    public void addHistory(long time, String reason, long amount) {
        int cap = Math.min(HARD_CAP, Math.max(1, SuiteConfig.bankHistorySize()));
        history.add(0, new Entry(time, reason == null ? "" : reason, amount, principal));
        while (history.size() > cap) {
            history.remove(history.size() - 1);
        }
    }

    /** 旧档兼容 + 配置变小后的自愈：把流水裁剪到当前上限。 */
    public void validate() {
        int cap = Math.min(HARD_CAP, Math.max(1, SuiteConfig.bankHistorySize()));
        while (history.size() > cap) {
            history.remove(history.size() - 1);
        }
        if (lastInterestAt < 0L) {
            lastInterestAt = 0L;
        }
        if (lastWithdrawAt < 0L) {
            lastWithdrawAt = 0L;
        }
        if (principal < 0L) {
            principal = 0L;
        }
    }

    // ------------------------------------------------------------------ 序列化

    public static final IAttachmentSerializer<CompoundTag, BankData> SERIALIZER = new Serializer();

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, BankData> {
        @Override
        public BankData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            BankData data = new BankData();
            data.readFrom(tag);
            return data;
        }

        @Override
        public CompoundTag write(BankData data, HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            data.writeTo(tag);
            return tag.isEmpty() ? null : tag;
        }
    }

    /** 缺键全部按默认值处理，旧档（没有本附件或字段不全）不会崩。 */
    public void readFrom(CompoundTag tag) {
        principal = Math.max(0L, tag.getLong(TAG_PRINCIPAL));
        lastInterestAt = Math.max(0L, tag.getLong(TAG_INTEREST_AT));
        lastWithdrawAt = Math.max(0L, tag.getLong(TAG_WITHDRAW_AT));
        frozen = tag.getBoolean(TAG_FROZEN);
        history.clear();
        if (tag.contains(TAG_HISTORY, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                history.add(Entry.fromTag(list.getCompound(i)));
            }
        }
        validate();
    }

    public void writeTo(CompoundTag tag) {
        if (principal > 0L) {
            tag.putLong(TAG_PRINCIPAL, principal);
        }
        if (lastInterestAt > 0L) {
            tag.putLong(TAG_INTEREST_AT, lastInterestAt);
        }
        if (lastWithdrawAt > 0L) {
            tag.putLong(TAG_WITHDRAW_AT, lastWithdrawAt);
        }
        if (frozen) {
            tag.putBoolean(TAG_FROZEN, true);
        }
        if (!history.isEmpty()) {
            ListTag list = new ListTag();
            for (Entry entry : history) {
                list.add(entry.toTag());
            }
            tag.put(TAG_HISTORY, list);
        }
    }
}
