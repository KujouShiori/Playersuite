package dev.mcmods.playersuite.client;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端接线：把仓库界面绑定到容器菜单类型。
 *
 * <p>{@code value = Dist.CLIENT} 保证该类在专用服务端不会被加载；
 * {@code bus = Bus.MOD} 则是必需的：{@link RegisterMenuScreensEvent} 发生在 mod 总线，
 * 而 {@code @EventBusSubscriber} 的默认总线是 GAME，不写 bus 的话永远收不到事件。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WarehouseClient {
    private WarehouseClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.WAREHOUSE_MENU.get(), WarehouseScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> WarehouseScreen", ModMenus.WAREHOUSE_MENU.getId());
    }
}
