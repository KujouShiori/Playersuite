package dev.mcmods.playersuite.warehouse;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.config.WarehouseConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.WarehouseMenu;
import dev.mcmods.playersuite.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;

/**
 * 个人仓库的业务逻辑：打开界面、扩充容量、整理、信息查询与提示文本。
 *
 * <p>所有方法都只在逻辑服务端调用。
 */
public final class WarehouseService {
    private WarehouseService() {
    }

    // ---------------------------------------------------------------- 数据访问

    public static WarehouseData data(Player player) {
        return player.getData(ModAttachments.WAREHOUSE);
    }

    /** 读取前做一次自愈，保证容量不小于等级应有的容量（配置调大时生效）。 */
    public static WarehouseData prepared(Player player) {
        WarehouseData data = data(player);
        data.validate();
        return data;
    }

    public static int levelOf(Player player) {
        return data(player).getLevel();
    }

    // ---------------------------------------------------------------- 分页

    public static int pageCount(int rows) {
        int perPage = WarehouseConfig.rowsPerPage();
        return Math.max(1, (rows + perPage - 1) / perPage);
    }

    public static int clampPage(int page, int rows) {
        return Mth.clamp(page, 0, pageCount(rows) - 1);
    }

    // ---------------------------------------------------------------- 打开界面

    /**
     * 以 {@code viewer} 的视角打开 {@code owner} 的个人仓库。
     *
     * @param page 起始页码，从 0 开始
     */
    public static void open(ServerPlayer viewer, ServerPlayer owner, int page) {
        WarehouseData data = prepared(owner);
        int rows = data.rows();
        int perPage = WarehouseConfig.rowsPerPage();
        int pages = pageCount(rows);
        int clamped = clampPage(page, rows);
        boolean manage = canManage(viewer, owner);

        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, player) -> new WarehouseMenu(containerId, inventory, viewer, owner, data, clamped),
                title(owner));

        int capacity = data.capacity();
        int level = data.getLevel();
        int balance = Economy.intForGui(Economy.balance(viewer));
        int price = data.nextPrice();
        viewer.openMenu(provider, buf -> {
            // 与 WarehouseMenu 客户端构造函数的读取顺序严格一致
            buf.writeInt(capacity);
            buf.writeInt(rows);
            buf.writeInt(clamped);
            buf.writeInt(pages);
            buf.writeInt(perPage);
            buf.writeInt(level);
            buf.writeInt(balance);
            buf.writeInt(price);
            buf.writeBoolean(manage);
        });
    }

    public static Component title(ServerPlayer owner) {
        String name = owner.getGameProfile().getName();
        if (name == null || name.isBlank()) {
            return Component.translatable("playersuite.title");
        }
        return Component.translatable("playersuite.title.of", name);
    }

    /** 当前查看者是否可以对该仓库执行扩充/整理等写操作。 */
    public static boolean canManage(ServerPlayer viewer, ServerPlayer owner) {
        if (isSamePlayer(viewer, owner)) {
            return true;
        }
        return SuiteConfig.allowManageOthers() && isOp(viewer);
    }

    // ---------------------------------------------------------------- 扩充

    /**
     * 花费余额扩充一次仓库容量。
     *
     * @param currentPage 当前界面页码，扩充后按同样的页码重新打开界面
     * @return 是否成功扩充
     */
    public static boolean upgrade(ServerPlayer payer, ServerPlayer target, int currentPage) {
        WarehouseData data = prepared(target);
        if (data.isMaxLevel()) {
            payer.displayClientMessage(Component.translatable("playersuite.msg.upgrade.maxed",
                    data.getLevel()), true);
            return false;
        }
        long price = data.nextPrice();
        if (price > 0 && !Economy.withdraw(payer, price, "warehouse_upgrade")) {
            payer.displayClientMessage(Component.translatable("playersuite.msg.upgrade.shortage",
                    Economy.label(price)), true);
            return false;
        }
        int newLevel = data.getLevel() + 1;
        data.setLevel(newLevel);
        payer.displayClientMessage(Component.translatable("playersuite.msg.upgraded",
                newLevel, data.capacity(), price <= 0 ? Component.literal("0") : Economy.label(price)), false);
        payer.playSound(net.minecraft.sounds.SoundEvents.ANVIL_USE, 0.8F, 1.4F);
        // 重新打开：槽位数量变了，必须重建容器
        open(payer, target, currentPage);
        return true;
    }

    /**
     * 管理员强制设置等级（不收费，自动扩容）。
     */
    public static void setLevel(ServerPlayer target, int level) {
        WarehouseData data = prepared(target);
        int max = WarehouseConfig.maxLevel();
        data.setLevel(Mth.clamp(level, 0, max));
        target.displayClientMessage(Component.translatable("playersuite.level.set",
                target.getGameProfile().getName(), data.getLevel(), data.capacity()), false);
    }

    // ---------------------------------------------------------------- 整理

    /** 整理仓库（{@code viewer} 发起，整理 {@code target} 的仓库）。 */
    public static void sort(ServerPlayer viewer, ServerPlayer target) {
        if (!canManage(viewer, target)) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.upgrade.noPermission"), true);
            return;
        }
        WarehouseData data = prepared(target);
        data.sort();
        viewer.displayClientMessage(Component.translatable("playersuite.msg.sorted"), true);
        // 物品变化由容器自动同步，无需重开界面
    }

    // ---------------------------------------------------------------- 信息

    public static Component infoLine(ServerPlayer viewer, ServerPlayer target) {
        WarehouseData data = prepared(target);
        long balance = Economy.balance(target);
        if (isSamePlayer(viewer, target)) {
            long price = data.nextPrice();
            if (price < 0) {
                return Component.translatable("playersuite.info.maxed",
                        data.getLevel(), data.capacity(), Economy.label(balance));
            }
            return Component.translatable("playersuite.info.self",
                    data.getLevel(), data.capacity(), Economy.label(price), Economy.label(balance));
        }
        return Component.translatable("playersuite.info.other",
                target.getGameProfile().getName(), data.getLevel(), data.capacity(), Economy.label(balance));
    }

    /** 可点击的「打开个人仓库」文本，用于进服提示与 /warehouse info。 */
    public static Component openLink() {
        // withStyle(ChatFormatting) 与 withStyle(Consumer<Style>) 都是 MutableComponent 上的方法，
        // 因此必须从 Component.translatable(...) 直接链式调用，不能先存成 Component 变量。
        return Component.translatable("text.playersuite.clickToOpen")
                .withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND, "/" + SuiteConfig.commandRoot() + " open")));
    }

    // ---------------------------------------------------------------- 权限判定

    /** 两个玩家是否为同一人（允许传 null，null 一律判为不同）。 */
    public static boolean isSamePlayer(Player a, Player b) {
        return a != null && b != null && a.getUUID().equals(b.getUUID());
    }

    public static boolean isOp(ServerPlayer player) {
        // 等级 2 = GAME_MASTER，与指令里的 Commands.LEVEL_GAMEMASTERS 一致
        return player.hasPermissions(2);
    }

}
