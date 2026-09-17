package dev.mcmods.playersuite.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 货币提供方接口：所有功能（仓库扩容、银行、商店、市场、称号、签到）都通过
 * {@link Economy} 间接使用它，因此只要换一个实现就能接入别的经济系统。
 *
 * <p>要对接其它经济 mod，在 mod 构造阶段调用
 * {@code Economy.bind(new YourProvider())} 即可，全部功能会一起切换。
 */
public interface CurrencyProvider {
    /** 用于日志与调试的标识。 */
    String id();

    /** 玩家当前余额（不小于 0）。 */
    long balance(ServerPlayer player);

    /** 扣款；余额不足时返回 false 且不做任何变动。 */
    boolean withdraw(ServerPlayer player, long amount);

    /** 加钱（实现方需自行处理上限）。 */
    void deposit(ServerPlayer player, long amount);

    /** 金额显示文本，例如 {@code 金 1,234}。 */
    Component format(long amount);

    /**
     * 管理员直接设置余额；不支持该操作的经济系统可以返回 false。
     * 默认实现：先清零再补足。
     */
    default boolean setBalance(ServerPlayer player, long amount) {
        long delta = amount - balance(player);
        if (delta > 0) {
            deposit(player, delta);
        } else if (delta < 0 && !withdraw(player, -delta)) {
            return false;
        }
        return true;
    }

    /** 是否支持在玩家离线时记录待领取收入（内置钱包支持；外部经济一般不支持）。 */
    default boolean supportsOfflinePayout() {
        return false;
    }
}
