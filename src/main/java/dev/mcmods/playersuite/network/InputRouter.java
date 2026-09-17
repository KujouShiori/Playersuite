package dev.mcmods.playersuite.network;

import dev.mcmods.playersuite.bank.BankService;
import dev.mcmods.playersuite.checkin.CheckinService;
import dev.mcmods.playersuite.mail.MailService;
import dev.mcmods.playersuite.market.MarketService;
import dev.mcmods.playersuite.shop.ShopService;
import dev.mcmods.playersuite.title.TitleService;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 文本输入的服务端分发。
 *
 * <p>各功能只需要在自己的 Service 里实现
 * {@code onTextInput(ServerPlayer, String action, String text)}，
 * 然后在下面的 switch 中登记一行即可（集成时统一接线）。
 */
public final class InputRouter {
    private InputRouter() {
    }

    public static void handle(Player player, TextInputPayload payload) {
        if (!(player instanceof ServerPlayer sender)) {
            return;
        }
        String feature = payload.feature() == null ? "" : payload.feature();
        String action = payload.action() == null ? "" : payload.action();
        String text = payload.text() == null ? "" : payload.text().trim();
        if (text.length() > TextInputPayload.MAX_TEXT) {
            sender.displayClientMessage(Component.translatable("playersuite.error.textTooLong"), false);
            return;
        }
        switch (feature) {
            case FeatureOpeners.MAIL -> MailService.onTextInput(sender, action, text);
            case FeatureOpeners.BANK -> BankService.onTextInput(sender, action, text);
            case FeatureOpeners.SHOP -> ShopService.onTextInput(sender, action, text);
            case FeatureOpeners.MARKET -> MarketService.onTextInput(sender, action, text);
            case FeatureOpeners.TITLE -> TitleService.onTextInput(sender, action, text);
            case FeatureOpeners.CHECKIN -> CheckinService.onTextInput(sender, action, text);
            default -> {
                if (!FeatureOpeners.HUB.equals(feature)) {
                    sender.displayClientMessage(Component.translatable("playersuite.ui.unknownFeature", feature), false);
                }
            }
        }
    }
}
