package dev.mcmods.playersuite.network;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 自定义网络包的注册（双端都会加载本类，因此这里不能引用任何客户端类）。
 *
 * <p>注意 {@code bus = Bus.MOD}：{@link RegisterPayloadHandlersEvent} 只发生在 mod 总线上，
 * 而 {@code @EventBusSubscriber} 的默认总线是 GAME。
 *
 * <p>客户端发送入口在 {@code client/ClientNet#sendText}。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SuiteNet {
    private SuiteNet() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PlayerSuiteMod.MODID).versioned("1");
        registrar.playToServer(TextInputPayload.TYPE, TextInputPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> InputRouter.handle(context.player(), payload)));
        PlayerSuiteMod.LOGGER.info("[network] 已注册自定义包：{}", TextInputPayload.TYPE.id());
    }
}
