package dev.mcmods.playersuite.client.shop;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.shop.ShopMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 商店客户端接线：把两个页面绑定到各自的容器菜单类型。
 *
 * <p>{@code value = Dist.CLIENT} 保证专用服务端不加载本类；
 * {@code bus = Bus.MOD}：{@link RegisterMenuScreensEvent} 在 mod 总线上发出。
 */
@EventBusSubscriber(modid = PlayerSuiteMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ShopClient {
    private ShopClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ShopMenus.SHOP_MENU.get(), ShopScreen::new);
        event.register(ShopMenus.SHOP_BROWSE_MENU.get(), ShopBrowseScreen::new);
        PlayerSuiteMod.LOGGER.info("[client] 已注册界面：{} -> ShopScreen，{} -> ShopBrowseScreen",
                ShopMenus.SHOP_MENU.getId(), ShopMenus.SHOP_BROWSE_MENU.getId());
    }
}
