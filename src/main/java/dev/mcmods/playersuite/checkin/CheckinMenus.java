package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 签到容器菜单注册：{@code playersuite:checkin}。
 *
 * <p>只签到页一个菜单类型：本周期天数超过 9 天时用同一菜单翻页显示（页码走 {@code PageMenu} 的公共分页），
 * 补签模式只是客户端的显示开关，不需要第二个菜单。
 */
public final class CheckinMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CheckinMenu>> CHECKIN_MENU =
            MENUS.register("checkin", () -> IMenuTypeExtension.create(CheckinMenu::new));

    private CheckinMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
