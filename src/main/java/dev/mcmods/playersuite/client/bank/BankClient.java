package dev.mcmods.playersuite.client.bank;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.bank.BankMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 银行客户端接线：把 {@code playersuite:bank} 容器绑定到 {@link BankScreen}。
 *
 * <p>{@code bus = Bus.MOD} 是必需的（{@link RegisterMenuScreensEvent} 在 mod 总线），
 * {@code value = Dist.CLIENT} 保证专用服务端不加载本类。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BankClient {
    private BankClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(BankMenus.BANK_MENU.get(), BankScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> BankScreen", BankMenus.BANK_MENU.getId());
    }
}
