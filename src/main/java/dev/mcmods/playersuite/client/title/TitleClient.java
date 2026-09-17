package dev.mcmods.playersuite.client.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.title.TitleMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 称号客户端接线：把 {@link TitleScreen} 绑定到 playersuite:title 菜单类型。
 *
 * <p>{@code RegisterMenuScreensEvent} 在 mod 总线上触发，必须显式写
 * {@code bus = EventBusSubscriber.Bus.MOD}（默认是 GAME，不写永远收不到事件）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TitleClient {

    private TitleClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TitleMenus.TITLE_MENU.get(), TitleScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> TitleScreen", TitleMenus.TITLE_MENU.getId());
    }
}
