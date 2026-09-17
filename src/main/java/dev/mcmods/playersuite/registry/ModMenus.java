package dev.mcmods.playersuite.registry;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.hub.HubMenu;
import dev.mcmods.playersuite.menu.WarehouseMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 容器菜单类型注册。使用 {@link IMenuTypeExtension#create} 以便把服务端数据
 * （容量、等级、页码、余额、价格）通过 openMenu 的附加数据传给客户端。
 */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<WarehouseMenu>> WAREHOUSE_MENU =
            MENUS.register("warehouse", () -> IMenuTypeExtension.create(WarehouseMenu::new));

    /** 总入口（一条指令打开的首页）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<HubMenu>> HUB_MENU =
            MENUS.register("hub", () -> IMenuTypeExtension.create(HubMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
