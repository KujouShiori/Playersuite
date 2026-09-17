package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 签到的游戏总线事件。
 *
 * <p>只做一件事：玩家进服时，如果<b>今天还没签到</b>，发一条带可点击链接的提示
 * （链接写法与 {@code BankService.openLink()} 一致，点击执行 {@code /ps checkin}）。
 *
 * <p>{@code @EventBusSubscriber} 默认就是 GAME 总线，本类只用游戏事件；
 * 顺手在这里把周期滚到「包含今天」（读取附件时的一次性自愈，不发任何奖励）。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID)
public final class CheckinEvents {
    private CheckinEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureKeys.CHECKIN)) {
            return;
        }
        // data(player) 会把 cycleStartDay 推进到包含今天的周期（跨周期时清空已领标记与补签次数）
        CheckinService.data(player);
        if (SuiteConfig.joinHint() && !CheckinService.isCheckedInToday(player)) {
            player.displayClientMessage(Component.translatable("playersuite.checkin.msg.joinHint")
                    .append(Component.literal(" "))
                    .append(CheckinService.openLink()), false);
        }
    }
}
