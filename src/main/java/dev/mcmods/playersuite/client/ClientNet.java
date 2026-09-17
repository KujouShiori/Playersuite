package dev.mcmods.playersuite.client;

import dev.mcmods.playersuite.network.TextInputPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端发送文本输入的唯一入口（界面里的输入框回调它）。
 *
 * <p>放在客户端包内，保证 {@link Minecraft} 不会泄漏到专用服务端的类加载中。
 */
public final class ClientNet {
    private ClientNet() {
    }

    /**
     * @param feature 功能键（mail/bank/shop/market/title/checkin/admin…），见 {@code FeatureOpeners}
     * @param action  功能内部定义的动作名
     * @param text    玩家输入的文本
     */
    public static void sendText(String feature, String action, String text) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        String safe = text == null ? "" : text;
        if (safe.length() > TextInputPayload.MAX_TEXT) {
            safe = safe.substring(0, TextInputPayload.MAX_TEXT);
        }
        PacketDistributor.sendToServer(new TextInputPayload(feature, action, safe));
    }
}
