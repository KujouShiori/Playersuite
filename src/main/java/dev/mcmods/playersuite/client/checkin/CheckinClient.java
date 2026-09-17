package dev.mcmods.playersuite.client.checkin;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.checkin.CheckinMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 签到客户端接线：把 {@code playersuite:checkin} 容器绑定到 {@link CheckinScreen}。
 *
 * <p>{@code RegisterMenuScreensEvent} 在 <b>MOD</b> 总线上，所以必须写
 * {@code bus = EventBusSubscriber.Bus.MOD}（会有 deprecation 警告，按项目约定忽略）；
 * {@code value = Dist.CLIENT} 保证专用服务端不加载本类。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CheckinClient {
    private CheckinClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(CheckinMenus.CHECKIN_MENU.get(), CheckinScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> CheckinScreen",
                CheckinMenus.CHECKIN_MENU.getId());
    }
}
