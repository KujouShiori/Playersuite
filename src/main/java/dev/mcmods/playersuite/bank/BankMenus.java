package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 银行容器菜单注册：{@code playersuite:bank}。
 *
 * <p>一个 MenuType 承担两种模式（主页 / 流水页），模式由容器附加数据下发，
 * 因此不需要为流水页再注册第二个菜单类型。
 */
public final class BankMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, PlayerSuiteMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<BankMenu>> BANK_MENU =
            MENUS.register("bank", () -> IMenuTypeExtension.create(BankMenu::new));

    private BankMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
