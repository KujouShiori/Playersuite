package dev.mcmods.playersuite.client;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.client.hub.HubScreen;
import dev.mcmods.playersuite.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端接线：总入口界面。
 *
 * <p>功能子页面（邮件/银行/商店/市场/称号/签到）各自带一个同构的注册类，
 * 统一使用 {@code bus = Bus.MOD}（{@link RegisterMenuScreensEvent} 在 mod 总线上）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SuiteClient {
    private SuiteClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.HUB_MENU.get(), HubScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> HubScreen", ModMenus.HUB_MENU.getId());
    }
}
