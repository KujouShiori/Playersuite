package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 官方市场的游戏总线事件（独立于共享的 {@code event/ModEvents}，避免与其他代理冲突）。
 *
 * <ul>
 *     <li>服务器启动：解析目录 + 清理失效条目状态（配置热改后也会在下一次打开时自动重解析）；</li>
 *     <li>关服：把 {@link MarketState} 置脏，确保随世界保存（NeoForge 自动落盘脏数据）；</li>
 *     <li>登出：清掉该玩家的内存界面状态与防连点记录。</li>
 * </ul>
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class MarketEvents {

    private MarketEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!SuiteConfig.featureEnabled(FeatureKeys.MARKET)) {
            PlayerSuiteMod.LOGGER.info("官方市场功能已关闭，跳过目录解析");
            return;
        }
        MarketService.warmUp(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        try {
            MarketState.of(event.getServer()).setDirty();
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("[market] 关服置脏失败：{}", e.toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MarketService.forget(player.getUUID());
        }
    }
}
