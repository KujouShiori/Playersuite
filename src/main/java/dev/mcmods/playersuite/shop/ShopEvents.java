package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 玩家商店的游戏总线事件（独立于共享的 {@code event/ModEvents}，避免并行冲突）。
 *
 * <ul>
 *     <li>登录：领取管理员退回的托管物品、货架与托管仓/目录对账；</li>
 *     <li>启动/关服：确保两个 SavedData 已创建并至少脏一次
 *         （NeoForge 随世界自动保存脏数据，这里只负责置脏）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class ShopEvents {
    private ShopEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SuiteConfig.featureEnabled(ShopService.FEATURE)) {
            return;
        }
        ShopService.onLogin(player);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ShopService.flushAll(event.getServer());
        PlayerSuiteMod.LOGGER.info("玩家商店已就绪：货架 {} / 人，税率 {}%，每页 {} 行",
                SuiteConfig.shopListings(), SuiteConfig.shopTaxPercent(), SuiteConfig.shopBrowsePageSize());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ShopService.flushAll(event.getServer());
    }
}
