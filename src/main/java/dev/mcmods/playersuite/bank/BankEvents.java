package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 银行的游戏侧事件：登录结算利息 + 进服可点击链接 + 服务器每 60 秒结算在线玩家。
 *
 * <p>利息结算必须幂等（见 {@link BankService#settleInterest(ServerPlayer)}），
 * 所以三个结算时机（打开页面 / 登录 / 定时）随便谁先跑都不会重复发钱。
 *
 * <p>{@code @EventBusSubscriber} 默认就是 GAME 总线，本类只用游戏事件。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class BankEvents {
    /** 定时结算间隔：60 秒 * 20 tick。 */
    private static final int SETTLE_INTERVAL_TICKS = 60 * 20;

    private BankEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.BANK)) {
            return;
        }
        // 先把离线期间满 24 小时的周期结掉，再给链接，玩家看到的就是最新数字。
        BankService.settleInterest(player);
        if (SuiteConfig.joinHint()) {
            player.displayClientMessage(Component.translatable("playersuite.bank.msg.joinHint")
                    .append(Component.literal(" "))
                    .append(BankService.openLink()), false);
        }
    }

    /**
     * 每 60 秒为所有在线玩家结一次息。
     *
     * <p>放在 tick 末尾（Post）而不是 Pre，避免在玩家刚进服/维度切换时读到半初始化的数据；
     * 结算本身对每个玩家都是 O(1)，人数再多也就一次时间戳比较。
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % SETTLE_INTERVAL_TICKS != 0) {
            return;
        }
        BankService.settleAll(server);
    }
}
