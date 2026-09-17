package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 玩家商店的菜单类型注册（两个页面：我的商店 / 逛店）。
 *
 * <p>必须用 {@link IMenuTypeExtension#create}，客户端构造函数签名为
 * {@code (int, Inventory, RegistryFriendlyByteBuf)}。
 */
public final class ShopMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    /** 我的商店（OP 管理他人时也用它）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP_MENU =
            MENUS.register("shop", () -> IMenuTypeExtension.create(ShopMenu::new));

    /** 逛店（全服在架列表）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<ShopBrowseMenu>> SHOP_BROWSE_MENU =
            MENUS.register("shop_browse", () -> IMenuTypeExtension.create(ShopBrowseMenu::new));

    private ShopMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
