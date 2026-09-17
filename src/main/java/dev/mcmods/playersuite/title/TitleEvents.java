package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 称号功能的游戏总线事件：
 *
 * <ul>
 *     <li>{@link ServerChatEvent}：{@code titleShowInChat} 为真且玩家佩戴称号时，
 *         按 {@code titlePrefixFormat()} 把前缀拼到消息最前面（格式串被改坏时退化为
 *         「[称号] 原版消息」）；</li>
 *     <li>{@link PlayerEvent.PlayerLoggedInEvent}：{@code titleShowOnJoin} 为真时
 *         私聊提示当前佩戴的称号（仅提醒本人，不广播，避免刷屏）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class TitleEvents {

    private TitleEvents() {
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!SuiteConfig.titleShowInChat() || !SuiteConfig.featureEnabled(FeatureKeys.TITLE)) {
            return;
        }
        final ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            Component decorated = TitleService.decorateChat(player, event.getMessage(), event.getRawText());
            if (decorated != null) {
                event.setMessage(decorated);
            }
        } catch (RuntimeException e) {
            // 兜底：任何意外都不得影响聊天本身（原消息保持原样）
            PlayerSuiteMod.LOGGER.warn("[title] 聊天称号注入失败：{}", player.getGameProfile().getName(), e);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SuiteConfig.titleShowOnJoin() || !SuiteConfig.featureEnabled(FeatureKeys.TITLE)) {
            return;
        }
        Component name = TitleService.equippedName(player);
        if (name == null) {
            return;
        }
        player.displayClientMessage(Component.translatable("playersuite.title.join.hint", name)
                .append(Component.literal(" "))
                .append(TitleService.openLink()), false);
    }
}
