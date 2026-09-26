package dev.mcmods.playersuite.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuConstructor;

/**
 * 与原版 {@code SimpleMenuProvider} 等价的容器提供者，唯一区别是
 * {@link #shouldTriggerClientSideContainerClosingOnOpen()} 返回 {@code false}。
 *
 * <p>为什么重要：原版在服务端"已经有容器开着、又要开新容器"时，会先给客户端发一个
 * {@code ClientboundCloseScreen} 再发 {@code ClientboundOpenScreen}。客户端于是走
 * {@code setScreen(null) -> setScreen(新界面)}，中间那次释放/重新捕获鼠标会让原版把光标坐标
 * 写成窗口中心（{@code MouseHandler#grabMouse}），玩家看到的就是"点翻页/切页时鼠标啪地跳回屏幕正中"，
 * 同时界面还会闪一下。
 *
 * <p>关掉这个"客户端侧关闭"后，客户端直接替换界面，不再经过 null：
 * 鼠标不动、不闪屏、也少一发数据包。翻页、领取、上架刷新、总入口与功能页之间的跳转都走这里。
 */
public record SuiteMenuProvider(MenuConstructor constructor, Component displayName) implements MenuProvider {

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return this.constructor.createMenu(containerId, inventory, player);
    }

    @Override
    public Component getDisplayName() {
        return this.displayName;
    }

    @Override
    public boolean shouldTriggerClientSideContainerClosingOnOpen() {
        return false;
    }
}
