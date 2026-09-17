package dev.mcmods.playersuite.network;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 唯一的「客户端 -> 服务端」自定义包：文本输入。
 *
 * <p>界面上凡是需要玩家打字的动作（写邮件、设定售价、输入金额、命名店铺、新建称号……）
 * 都用它把字符串送回服务端，由各功能的 Service 校验并生效。
 * 其余交互一律走容器按钮 {@code clickMenuButton}，避免为每个功能单独造包。
 */
public record TextInputPayload(String feature, String action, String text) implements CustomPacketPayload {
    /** 服务端会拒绝超过该长度的文本。 */
    public static final int MAX_TEXT = 1024;

    public static final Type<TextInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlayerSuiteMod.MODID, "text_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TextInputPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TextInputPayload::feature,
            ByteBufCodecs.STRING_UTF8, TextInputPayload::action,
            ByteBufCodecs.STRING_UTF8, TextInputPayload::text,
            TextInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
