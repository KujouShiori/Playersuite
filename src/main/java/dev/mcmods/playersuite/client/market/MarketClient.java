package dev.mcmods.playersuite.client.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.market.MarketMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 官方市场客户端接线：把 {@link MarketScreen} 绑定到 {@code playersuite:market} 菜单类型。
 *
 * <p>{@code RegisterMenuScreensEvent} 在 mod 总线触发，必须显式写
 * {@code bus = EventBusSubscriber.Bus.MOD}（默认是 GAME，不写收不到事件）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MarketClient {

    private MarketClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(MarketMenus.MARKET_MENU.get(), MarketScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> MarketScreen", MarketMenus.MARKET_MENU.getId());
    }
}
