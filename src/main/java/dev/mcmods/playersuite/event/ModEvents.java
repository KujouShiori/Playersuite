package dev.mcmods.playersuite.event;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.command.MarketCommand;
import dev.mcmods.playersuite.command.SuiteCommand;
import dev.mcmods.playersuite.command.WarehouseCommand;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.config.WarehouseConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import dev.mcmods.playersuite.warehouse.WarehouseData;
import dev.mcmods.playersuite.warehouse.WarehouseService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 游戏侧事件：指令注册 + 玩家进服初始化。
 *
 * <p>使用 {@link EventBusSubscriber} 挂载到 NeoForge 游戏事件总线（1.21.1 官方测试用例同款写法）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class ModEvents {
    private ModEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SuiteCommand.register(event.getDispatcher());
        WarehouseCommand.register(event.getDispatcher());
        MarketCommand.register(event.getDispatcher());
        // COMMON 配置在 mod 构造阶段就加载，因此这里能读到真实的自定义指令名。
        PlayerSuiteMod.LOGGER.info("功能总入口指令已注册：{}（启用功能 {}）",
                SuiteCommand.commandNames(), SuiteConfig.enabledFeatureList());
        PlayerSuiteMod.LOGGER.info("个人仓库指令已注册：{}（COMMON 配置已加载：{}）",
                WarehouseCommand.commandNames(), SuiteConfig.isLoaded());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        WarehouseData data = WarehouseService.prepared(player);
        Economy.grantInitial(player);

        if (SuiteConfig.joinHint()) {
            int pending = Economy.pendingCount(player);
            Component message = Component.translatable(pending > 0 ? "playersuite.msg.welcomePending" : "playersuite.msg.welcome", pending)
                    .append(Component.literal(" "))
                    .append(FeatureOpeners.hubLink());
            player.displayClientMessage(message, false);
        }
        PlayerSuiteMod.LOGGER.debug("玩家 {} 的仓库：等级 {}，容量 {} 格",
                player.getGameProfile().getName(), data.getLevel(), data.capacity());
    }

    /** 玩家重生（死亡后回来）时同样保证仓库容量与配置一致。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            WarehouseService.prepared(player);
        }
    }

    /** 服务器启动完成后打印一次生效配置，便于运维核对。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PlayerSuiteMod.LOGGER.info("个人仓库配置：0 级 {} 格，满级 {} 级 {} 格，货币模式 {}，指令 {}",
                WarehouseConfig.initialCapacity(), WarehouseConfig.maxLevel(),
                WarehouseConfig.maxCapacity(), Economy.provider().id(), WarehouseCommand.commandNames());
    }
}
