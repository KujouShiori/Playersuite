package dev.mcmods.playersuite.hub;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.mail.MailService;
import dev.mcmods.playersuite.registry.ModMenus;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.mcmods.playersuite.ui.SuiteMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * 总入口容器：一张卡片式页面，七个功能按钮 + 余额 + 待领取收入。
 *
 * <p>附加数据顺序：公共头（{@link PageMenu#writeHeader}）之后是 2 个 int：
 * 待领取笔数、启用位掩码（见 {@link FeatureOpeners#enabledMask()}）。
 */
public class HubMenu extends PageMenu {
    // ---- 界面几何（客户端 HubScreen 直接引用，保证槽位与按钮不错位）----
    public static final int BUTTON_TOP = 22;
    public static final int BUTTON_PITCH = 24;
    public static final int BUTTON_W = 80;
    public static final int BUTTON_H = 20;
    public static final int COL_LEFT = 8;
    public static final int COL_RIGHT = 88;
    public static final int CLAIM_TOP = BUTTON_TOP + 4 * BUTTON_PITCH;
    public static final int INVENTORY_LABEL_TOP = CLAIM_TOP + 26;
    public static final int INVENTORY_TOP = INVENTORY_LABEL_TOP + 12;
    public static final int PANEL_HEIGHT = INVENTORY_TOP + 4 * 18 + 6;

    /** 功能按钮编号 = BTN_CUSTOM + FeatureOpeners.FEATURES 下标。 */
    public static final int BTN_FEATURE_BASE = BTN_CUSTOM;
    /** 一键领取离线待领取收入。 */
    public static final int BTN_CLAIM = BTN_CUSTOM + 8;

    private int enabledMask;

    public HubMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        super(ModMenus.HUB_MENU.get(), containerId, inventory);
        applyHeader(readHeader(extraData));
        int[] sub = readSubData(extraData, 2);
        setExtra(sub[0]);
        this.enabledMask = sub[1];
        addPlayerInventoryAt(inventory, INVENTORY_TOP);
    }

    private HubMenu(int containerId, Inventory inventory, ServerPlayer viewer, int pending, int mask, boolean manage) {
        super(ModMenus.HUB_MENU.get(), containerId, inventory, viewer, 0, 1, manage);
        setExtra(pending);
        this.enabledMask = mask;
        addPlayerInventoryAt(inventory, INVENTORY_TOP);
    }

    public static void open(ServerPlayer player) {
        int pending = Economy.pendingCount(player);
        int unread = MailService.unread(player);
        int mask = FeatureOpeners.enabledMask();
        boolean manage = player.hasPermissions(SuiteConfig.managePermission());
        long balance = Economy.balance(player);
        player.openMenu(new SuiteMenuProvider(
                        (containerId, inventory, sender) -> new HubMenu(containerId, inventory, player, pending, mask, manage),
                        Component.translatable("playersuite.hub.title")),
                buf -> writeFull(buf, balance, 0, 1, manage, unread, pending, mask));
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        open(viewer);
    }

    /** 第 index 个功能是否启用（客户端按钮置灰用）。 */
    public boolean featureEnabled(int index) {
        return FeatureOpeners.enabledAt(enabledMask, index);
    }

    /** 待领取收入笔数。 */
    public int pendingCount() {
        return Math.max(0, extra());
    }

    /** 未读邮件数（总入口提示用，来自服务端 status 同步）。 */
    public int mailUnread() {
        return Math.max(0, status());
    }

    public static String featureOf(int buttonId) {
        int index = buttonId - BTN_FEATURE_BASE;
        return FeatureOpeners.keyAt(index);
    }

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (buttonId == BTN_CLAIM) {
            long claimed = Economy.claimPending(viewer);
            viewer.displayClientMessage(Component.translatable(claimed > 0L
                    ? "playersuite.hub.claimed" : "playersuite.hub.claimNone", Economy.label(claimed)), true);
            open(viewer);
            return true;
        }
        String feature = featureOf(buttonId);
        if (feature == null) {
            return false;
        }
        FeatureOpeners.open(viewer, feature, 0);
        return true;
    }
}
