package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 官方市场的菜单类型注册（{@code playersuite:market}）。
 *
 * <p>客户端构造走 {@link IMenuTypeExtension#create} 反射匹配的
 * {@code (int, Inventory, RegistryFriendlyByteBuf)} 三参构造。
 */
public final class MarketMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> MARKET_MENU =
            MENUS.register("market", () -> IMenuTypeExtension.create(MarketMenu::new));

    private MarketMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
