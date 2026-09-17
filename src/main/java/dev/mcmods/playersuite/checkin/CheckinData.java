package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * 每日签到数据（玩家数据附件 {@code playersuite:checkin}）。
 *
 * <p>只有五个数，全部随玩家存档走，不需要 SavedData：
 * <ul>
 *     <li>{@code lastDay}：最后一次成功签到的「天序号」（epochDay，见 {@link CheckinService#currentDay()}），
 *         0 表示从来没签过；</li>
 *     <li>{@code streak}：连续签到天数（跨天中断在签到时重算，读取时不改动）；</li>
 *     <li>{@code claimedMask}：本周期内已领标记，第 i 位 = 本周期第 i+1 天是否已领
 *         （{@code SuiteConfig.checkinCycleDays() <= 31}，用 long 存绰绰有余）；</li>
 *     <li>{@code makeupUsed}：本周期已经用掉的补签次数；</li>
 *     <li>{@code cycleStartDay}：本周期第 1 天对应的 epochDay（周期是<b>每个玩家自己的滚动周期</b>，
 *         从该玩家第一次打开签到页那天开始算，不是全服统一日历）。</li>
 * </ul>
 *
 * <p>读旧档：缺键一律用默认值；{@link #readFrom(CompoundTag)} 之后 {@link #validate()} 会把
 * 越界的天数、位掩码高位、负数全部夹回合法范围，保证不会因为配置事故或手工改档而崩溃。
 */
public final class CheckinData {
    private static final String TAG_LAST_DAY = "LastDay";
    private static final String TAG_STREAK = "Streak";
    private static final String TAG_MASK = "ClaimedMask";
    private static final String TAG_MAKEUP = "MakeupUsed";
    private static final String TAG_CYCLE_START = "CycleStartDay";

    /** streak 的硬上限（防止配置事故或改档把加成乘爆）。 */
    public static final int MAX_STREAK = 100000;
    /** claimedMask 支持的周期天数上限（long 的前 31 位；与配置 clamp 一致）。 */
    public static final int MAX_CYCLE_DAYS = 31;

    private long lastDay;
    private int streak;
    private long claimedMask;
    private int makeupUsed;
    private long cycleStartDay;

    public CheckinData() {
    }

    // ------------------------------------------------------------------ 只读

    /** 最后一次签到的 epochDay；0 表示从未签到。 */
    public long lastDay() {
        return lastDay;
    }

    /** 存储的连续签到天数（真正对外显示的口径见 {@link CheckinService#effectiveStreak}）。 */
    public int streak() {
        return streak;
    }

    /** 本周期已领位掩码，第 0 位 = 第 1 天。 */
    public long claimedMask() {
        return claimedMask;
    }

    /** 本周期已用掉的补签次数。 */
    public int makeupUsed() {
        return makeupUsed;
    }

    /** 本周期第 1 天的 epochDay；0 表示还没初始化。 */
    public long cycleStartDay() {
        return cycleStartDay;
    }

    /** 本周期第 index（0 起）天是否已领。越界一律当作「未领」。 */
    public boolean isClaimed(int index) {
        return index >= 0 && index < MAX_CYCLE_DAYS && (claimedMask & (1L << index)) != 0L;
    }

    /** 剩余可用补签次数（不小于 0）。 */
    public int makeupLeft() {
        return Math.max(0, SuiteConfig.checkinMakeupMax() - makeupUsed);
    }

    // ------------------------------------------------------------------ 写入

    void setLastDay(long day) {
        this.lastDay = Math.max(0L, day);
    }

    void setStreak(int value) {
        this.streak = Math.max(0, Math.min(MAX_STREAK, value));
    }

    void setMakeupUsed(int value) {
        this.makeupUsed = Math.max(0, value);
    }

    void setCycleStartDay(long day) {
        this.cycleStartDay = Math.max(0L, day);
    }

    /** 标记本周期第 index（0 起）天已领；越界忽略（调用方负责先夹好）。 */
    void markClaimed(int index) {
        if (index >= 0 && index < MAX_CYCLE_DAYS) {
            claimedMask |= 1L << index;
        }
    }

    /** 本周期有效位掩码（配置把周期天数改小之后，高位残留要丢掉）。 */
    public static long validMask(int cycleDays) {
        int days = Math.max(1, Math.min(MAX_CYCLE_DAYS, cycleDays));
        return days >= 63 ? -1L : (1L << days) - 1L;
    }

    /**
     * 将掩码里已领的位换成新的周期长度：天数被改小时，超出范围的天直接算「已领」，
     * 防止玩家靠改配置刷前面的奖励（宁可少给，不可多给）。
     */
    private long collapseMask(int oldDays, int newDays) {
        long oldMask = claimedMask & validMask(oldDays);
        if (newDays >= oldDays) {
            return oldMask;
        }
        long collapsed = 0L;
        int claimedCount = Long.bitCount(oldMask);
        for (int i = 0; i < Math.min(claimedCount, newDays); i++) {
            collapsed |= 1L << i;
        }
        return collapsed;
    }

    /**
     * 夹到合法范围：负数归零、streak 封顶、掩码高位丢弃。返回是否真的改了东西（用于决定是否落盘）。
     *
     * @param cycleDays 当前配置的周期天数
     */
    public boolean validate(int cycleDays) {
        int days = Math.max(1, Math.min(MAX_CYCLE_DAYS, cycleDays));
        boolean dirty = false;
        if (lastDay < 0L) {
            lastDay = 0L;
            dirty = true;
        }
        if (streak != Math.max(0, Math.min(MAX_STREAK, streak))) {
            setStreak(streak);
            dirty = true;
        }
        if (makeupUsed < 0) {
            makeupUsed = 0;
            dirty = true;
        }
        long clipped = collapseMask(days, days);
        if (clipped != claimedMask) {
            claimedMask = clipped;
            dirty = true;
        }
        return dirty;
    }

    /**
     * 周期滚动：把 {@code cycleStartDay} 推进到「包含今天的那个周期」，跨周期时清空已领标记与补签次数。
     *
     * <p>周期起点是玩家自己的滚动周期（第一次打开签到页那天 = 第 1 天），所以「本周期第几天」
     * 对每个玩家可能不同；配置把 {@code cycleDays} 改大改小都只影响之后的推进。
     *
     * @param cycleDays   当前配置的周期天数（1..31）
     * @param today       当前天序号（{@link CheckinService#currentDay()}）
     * @return 是否有字段被改动（调用方据此决定是否 {@code setData} 触发落盘）
     */
    public boolean rollTo(int cycleDays, long today) {
        int days = Math.max(1, Math.min(MAX_CYCLE_DAYS, cycleDays));
        boolean dirty = validate(days);
        if (cycleStartDay <= 0L) {
            // 新玩家或被改坏的档：从「最后一次签到的那天」开始建周期，没签过就从今天开始。
            cycleStartDay = lastDay > 0L ? lastDay : today;
            dirty = true;
        }
        long diff = today - cycleStartDay;
        if (diff < 0L) {
            // 服务器时钟被往回调过（或周被改大）：把今天重新放到「已经领过的那几天之后」，
            // 并把已标记按顺位重排——既不重复发奖，也不把玩家已经拿到的迚度吞掉。
            int used = Long.bitCount(claimedMask & validMask(days));
            long newStart = Math.max(0L, today - used);
            long rebuilt = 0L;
            for (int i = 0; i < Math.min(used, days); i++) {
                rebuilt |= 1L << i;
            }
            boolean changed = newStart != cycleStartDay || rebuilt != claimedMask;
            cycleStartDay = newStart;
            claimedMask = rebuilt;
            return changed || dirty;
        }
        if (diff >= days) {
            long steps = diff / days;
            cycleStartDay += steps * days;
            if (cycleStartDay > today) {
                cycleStartDay = today;
            }
            claimedMask = 0L;
            makeupUsed = 0;
            return true;
        }
        return dirty;
    }

    /** 本周期第几天（0 起，越界夹到合法范围）。 */
    public int dayIndex(long today, int cycleDays) {
        int days = Math.max(1, Math.min(MAX_CYCLE_DAYS, cycleDays));
        long diff = today - cycleStartDay;
        return (int) Math.max(0L, Math.min((long) days - 1L, diff));
    }

    /** 本周期一共领了几天（含补签）。 */
    public int claimedCount(int cycleDays) {
        return Long.bitCount(claimedMask & validMask(Math.max(1, Math.min(MAX_CYCLE_DAYS, cycleDays))));
    }

    // ------------------------------------------------------------------ 存档

    public void writeTo(CompoundTag tag) {
        if (lastDay != 0L) {
            tag.putLong(TAG_LAST_DAY, lastDay);
        }
        if (streak != 0) {
            tag.putInt(TAG_STREAK, streak);
        }
        if (claimedMask != 0L) {
            tag.putLong(TAG_MASK, claimedMask);
        }
        if (makeupUsed != 0) {
            tag.putInt(TAG_MAKEUP, makeupUsed);
        }
        if (cycleStartDay != 0L) {
            tag.putLong(TAG_CYCLE_START, cycleStartDay);
        }
    }

    /** 缺键全部按默认值处理，旧档（没有本附件或字段不全）不会崩。 */
    public void readFrom(CompoundTag tag) {
        lastDay = Math.max(0L, tag.getLong(TAG_LAST_DAY));
        streak = Math.max(0, Math.min(MAX_STREAK, tag.getInt(TAG_STREAK)));
        claimedMask = tag.getLong(TAG_MASK);
        makeupUsed = Math.max(0, tag.getInt(TAG_MAKEUP));
        cycleStartDay = Math.max(0L, tag.getLong(TAG_CYCLE_START));
        if (claimedMask < 0L) {
            // 手工改档塞了个负数（等于全 1）：当成没领，宁可少给。
            claimedMask = 0L;
        }
        validate(SuiteConfig.checkinCycleDays());
    }

    public static final IAttachmentSerializer<CompoundTag, CheckinData> SERIALIZER = new Serializer();

    private static final class Serializer implements IAttachmentSerializer<CompoundTag, CheckinData> {
        @Override
        public CheckinData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            CheckinData data = new CheckinData();
            data.readFrom(tag);
            return data;
        }

        @Override
        public CompoundTag write(CheckinData data, HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            data.writeTo(tag);
            return tag.isEmpty() ? null : tag;   // 返回 null 表示不落盘（全新玩家没东西可存）
        }
    }
}
