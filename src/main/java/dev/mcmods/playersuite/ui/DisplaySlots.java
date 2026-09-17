package dev.mcmods.playersuite.ui;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 只读展示槽：用于在功能界面里“显示”某个物品（市场商品、商店货架、邮件附件图标等），
 * 玩家不能从中取出，也不能放入，因此不会被刷物品。
 *
 * <p>真实物品进出一律走各功能自己的可写槽位或服务端发放逻辑。
 */
public final class DisplaySlots {
    private DisplaySlots() {
    }

    /** 一个只能看、不能碰的展示槽。 */
    public static Slot of(ItemStack stack, int x, int y) {
        SimpleContainer holder = new SimpleContainer(1);
        holder.setItem(0, stack == null ? ItemStack.EMPTY : stack);
        return new Slot(holder, 0, x, y) {
            @Override
            public boolean mayPlace(ItemStack itemStack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public ItemStack remove(int amount) {
                return ItemStack.EMPTY;
            }
        };
    }

    /** 空槽（占位用，例如没有附件的邮件）。 */
    public static Slot empty(int x, int y) {
        return of(ItemStack.EMPTY, x, y);
    }
}
