package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 称号容器菜单注册（playersuite:title）。
 *
 * <p>一个 MenuType 承载称号墙与称号详情两个页面（服务端用 mode 切换），
 * 客户端构造走 {@link IMenuTypeExtension#create} 的 (id, inventory, buf) 反射匹配。
 */
public final class TitleMenus {

    public static final DeferredRegister<MenuType<?>> TITLE_MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<TitleMenu>> TITLE_MENU =
            TITLE_MENUS.register("title", () -> IMenuTypeExtension.create(TitleMenu::new));

    private TitleMenus() {
    }

    public static void register(IEventBus modEventBus) {
        TITLE_MENUS.register(modEventBus);
    }
}
