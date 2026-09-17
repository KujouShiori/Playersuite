package dev.mcmods.playersuite.client.mail;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.mail.MailMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 邮件客户端接线：把 {@link MailScreen} 绑定到 playersuite:mail 菜单类型。
 *
 * <p>{@code RegisterMenuScreensEvent} 在 mod 总线上触发，必须显式写
 * {@code bus = EventBusSubscriber.Bus.MOD}（默认是 GAME，不写永远收不到事件）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MailClient {

    private MailClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(MailMenus.MAIL_MENU.get(), MailScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> MailScreen", MailMenus.MAIL_MENU.getId());
    }
}
