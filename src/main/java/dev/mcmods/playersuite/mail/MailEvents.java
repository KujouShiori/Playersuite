package dev.mcmods.playersuite.mail;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 邮件功能的游戏侧事件：玩家登录后搬运离线暂存邮件/公告，并按配置提示未读数量。
 *
 * <p>{@code @EventBusSubscriber} 默认挂在游戏总线（GAME），与 {@code event/ModEvents} 同款写法；
 * 本类不重复处理 ModEvents 已做的初始化，只做邮件自己的事。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class MailEvents {

    private MailEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 投递与功能开关无关：曾经发来的邮件不能因为临时关功能而丢失
        MailService.deliverOnLogin(player);
        if (!SuiteConfig.mailNotifyOnLogin() || !SuiteConfig.featureEnabled(FeatureKeys.MAIL)) {
            return;
        }
        int unread = MailService.unread(player);
        if (unread <= 0) {
            return;
        }
        player.displayClientMessage(Component.translatable("playersuite.mail.loginNotify", unread)
                .append(Component.literal(" "))
                .append(MailService.openLink()), false);
    }
}
