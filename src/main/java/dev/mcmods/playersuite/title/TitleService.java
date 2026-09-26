package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import dev.mcmods.playersuite.ui.SuiteMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 称号业务层（服务端权威）：称号墙/详情页打开、购买、佩戴、OP 授予/撤销/强制佩戴、
 * 聊天前缀注入与进服播报。
 *
 * <p>接线点（已在 {@code PlayerSuiteMod}、{@code FeatureOpeners}、{@code InputRouter} 中完成）：
 * <ul>
 *     <li>{@code FeatureOpeners.open}：{@code case FeatureOpeners.TITLE -> TitleService.open(player, player, page);}</li>
 *     <li>{@code InputRouter.handle}：{@code case FeatureOpeners.TITLE -> TitleService.onTextInput(sender, action, text);}</li>
 *     <li>模组构造方法：{@code TitleAttachments.register(modEventBus); TitleMenus.register(modEventBus);}</li>
 * </ul>
 */
public final class TitleService {

    /** openTextInput 的动作名。 */
    public static final String INPUT_GRANT = "grant";
    public static final String INPUT_REVOKE = "revoke";
    public static final String INPUT_VIEW_TARGET = "view_target";

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_INPUT_LEN = 64;

    private TitleService() {
    }

    // ---------------------------------------------------------------- 数据访问

    public static TitleData data(Player player) {
        return player.getData(TitleAttachments.TITLE.get());
    }

    private static void save(Player player, TitleData data) {
        player.setData(TitleAttachments.TITLE.get(), data);
    }

    private static boolean featureOn(ServerPlayer player) {
        if (SuiteConfig.featureEnabled(FeatureKeys.TITLE)) {
            return true;
        }
        player.displayClientMessage(Component.translatable("playersuite.title.err.featureOff"), false);
        return false;
    }

    /** 能否查看/管理他人称号页（默认 OP2 + allowManageOthers）。 */
    public static boolean canManage(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.managePermission());
    }

    /** 能否授予/撤销/强制佩戴（默认 OP3 + allowManageOthers）。 */
    public static boolean canAdmin(ServerPlayer viewer) {
        return SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.adminPermission());
    }

    public static boolean isSamePlayer(Player a, Player b) {
        return a != null && b != null && a.getUUID().equals(b.getUUID());
    }

    private static int flagsFor(ServerPlayer viewer, boolean self) {
        int flags = 0;
        if (!self) {
            flags |= TitleMenu.FLAG_OTHER;
        }
        if (canManage(viewer)) {
            flags |= TitleMenu.FLAG_MANAGE;
        }
        if (canAdmin(viewer)) {
            flags |= TitleMenu.FLAG_ADMIN;
        }
        return flags;
    }

    // ---------------------------------------------------------------- 打开页面

    /**
     * 以 {@code viewer} 视角打开 {@code target} 的称号墙。
     * 看他人需要 managePermission + allowManageOthers，否则回落到自己的页。
     */
    public static void open(ServerPlayer viewer, ServerPlayer target, int page) {
        if (viewer == null || viewer.isRemoved()) {
            return;
        }
        if (!featureOn(viewer)) {
            return;
        }
        if (target == null || target.isRemoved()) {
            target = viewer;
        }
        boolean self = isSamePlayer(viewer, target);
        if (!self && !canManage(viewer)) {
            viewer.displayClientMessage(
                    Component.translatable("playersuite.msg.noPermission", SuiteConfig.managePermission()), false);
            target = viewer;
            self = true;
        }
        openGrid(viewer, target, flagsFor(viewer, self), page);
    }

    private static void openGrid(ServerPlayer viewer, ServerPlayer target, int flags, int page) {
        List<TitleCatalog.Entry> catalog = TitleCatalog.entries();
        TitleData data = data(target);
        int total = catalog.size();
        int pages = Math.max(1, (total + TitleMenu.PER_PAGE - 1) / TitleMenu.PER_PAGE);
        int clamped = Mth.clamp(page, 0, pages - 1);
        int from = clamped * TitleMenu.PER_PAGE;
        int cells = Mth.clamp(total - from, 0, TitleMenu.PER_PAGE);
        List<TitleCatalog.Entry> pageEntries = new ArrayList<>(catalog.subList(from, from + cells));
        List<Integer> bits = new ArrayList<>(cells);
        for (TitleCatalog.Entry entry : pageEntries) {
            bits.add(bitsFor(data, entry.id()));
        }
        int owned = data.ownedCount();
        int maxOwned = SuiteConfig.titleMaxOwned();
        long balance = Economy.balance(viewer);
        final ServerPlayer shown = target;

        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> new TitleMenu(containerId, inventory, viewer, shown, data,
                        clamped, pages, flags, owned, total, maxOwned, pageEntries, bits),
                pageTitle(shown, viewer, flags));
        viewer.openMenu(provider, buf -> {
            TitleMenu.writeTitleBuf(buf, balance, clamped, pages, (flags & TitleMenu.FLAG_MANAGE) != 0, owned,
                    TitleMenu.MODE_GRID, flags, owned, total, cells, -1, maxOwned);
            TitleMenu.writeGridCells(buf, pageEntries, bits);
        });
    }

    /** 打开第 index 个（全局目录下标）称号的详情。 */
    private static void openDetail(ServerPlayer viewer, ServerPlayer target, int index, int gridPage) {
        TitleCatalog.Entry entry = TitleCatalog.byIndex(index);
        if (entry == null) {
            openGrid(viewer, target, flagsFor(viewer, isSamePlayer(viewer, target)), gridPage);
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", "?"), true);
            return;
        }
        boolean self = isSamePlayer(viewer, target);
        int flags = flagsFor(viewer, self);
        TitleData data = data(target);
        int total = TitleCatalog.entries().size();
        int maxOwned = SuiteConfig.titleMaxOwned();
        int bits = bitsFor(data, entry.id());
        int owned = data.ownedCount();
        long balance = Economy.balance(viewer);
        final ServerPlayer shown = target;
        final int selected = index;

        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> new TitleMenu(containerId, inventory, viewer, shown, data,
                        flags, owned, total, maxOwned, selected, gridPage, entry, bits),
                pageTitle(shown, viewer, flags));
        viewer.openMenu(provider, buf -> {
            TitleMenu.writeTitleBuf(buf, balance, 0, 1, (flags & TitleMenu.FLAG_MANAGE) != 0, owned,
                    TitleMenu.MODE_DETAIL, flags, owned, total, 0, selected, maxOwned);
            TitleMenu.writeDetailData(buf, entry, bits);
        });
    }

    /** 称号墙第 row 格的「查」按钮。 */
    public static boolean openRowAt(ServerPlayer viewer, TitleMenu menu, int row) {
        if (menu.mode() != TitleMenu.MODE_GRID || !menu.viewerIs(viewer)) {
            return false;
        }
        int index = menu.page() * TitleMenu.PER_PAGE + row;
        ServerPlayer target = menu.targetOrSelf(viewer);
        if (index < 0 || index >= TitleCatalog.entries().size()) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", "?"), true);
            open(viewer, target, menu.page());
            return true;
        }
        openDetail(viewer, target, index, menu.page());
        return true;
    }

    private static Component pageTitle(ServerPlayer target, ServerPlayer viewer, int flags) {
        if ((flags & TitleMenu.FLAG_OTHER) != 0) {
            return Component.translatable("playersuite.title.pageOf", target.getGameProfile().getName());
        }
        return Component.translatable("playersuite.title.page");
    }

    private static int bitsFor(TitleData data, String id) {
        int bits = 0;
        if (data.owns(id)) {
            bits |= TitleMenu.BIT_OWNED;
        }
        if (data.isEquipped(id)) {
            bits |= TitleMenu.BIT_EQUIPPED;
        }
        return bits;
    }

    // ---------------------------------------------------------------- 本人动作

    /** 购买（价格 0 = 免费直接领取，与配置注释一致；扣钱成功才写入拥有表）。 */
    public static boolean purchase(ServerPlayer viewer, TitleMenu menu) {
        if (menu.mode() != TitleMenu.MODE_DETAIL || !menu.viewerIs(viewer)) {
            return false;
        }
        if (!featureOn(viewer)) {
            return true;
        }
        ServerPlayer target = menu.targetOrSelf(viewer);
        if (!isSamePlayer(viewer, target)) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.viewingOther"), false);
            return true;
        }
        TitleCatalog.Entry entry = TitleCatalog.byId(menu.detailId());
        if (entry == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", menu.detailId()), true);
            return true;
        }
        TitleData data = data(viewer);
        if (data.owns(entry.id())) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.msg.alreadyOwned"), true);
            return true;
        }
        int maxOwned = SuiteConfig.titleMaxOwned();
        if (data.ownedCount() >= maxOwned) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.maxOwned", maxOwned), false);
            return true;
        }
        if (entry.price() > 0L && !Economy.withdraw(viewer, entry.price(), "title_buy")) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.money",
                    Economy.label(entry.price())), false);
            return true;
        }
        data.addOwned(entry.id());
        save(viewer, data);
        viewer.displayClientMessage(entry.price() > 0L
                ? Component.translatable("playersuite.title.msg.bought", entry.displayName(), Economy.label(entry.price()))
                : Component.translatable("playersuite.title.msg.freeClaimed", entry.displayName()), false);
        ding(viewer);
        openDetail(viewer, viewer, indexOf(entry.id()), menu.gridPage());
        return true;
    }

    /** 佩戴（详情页按钮；必须已拥有）。 */
    public static boolean equipSelf(ServerPlayer viewer, TitleMenu menu) {
        if (menu.mode() != TitleMenu.MODE_DETAIL || !menu.viewerIs(viewer)) {
            return false;
        }
        if (!featureOn(viewer)) {
            return true;
        }
        ServerPlayer target = menu.targetOrSelf(viewer);
        if (!isSamePlayer(viewer, target)) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.viewingOther"), false);
            return true;
        }
        TitleCatalog.Entry entry = TitleCatalog.byId(menu.detailId());
        if (entry == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", menu.detailId()), true);
            return true;
        }
        TitleData data = data(viewer);
        if (!data.owns(entry.id())) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.notOwned"), false);
            return true;
        }
        data.setEquipped(entry.id());
        save(viewer, data);
        viewer.displayClientMessage(Component.translatable("playersuite.title.msg.equipped", entry.displayName()), false);
        ding(viewer);
        openDetail(viewer, viewer, indexOf(entry.id()), menu.gridPage());
        return true;
    }

    /** 卸下当前佩戴。 */
    public static boolean unequipSelf(ServerPlayer viewer, TitleMenu menu) {
        if (menu.mode() != TitleMenu.MODE_DETAIL || !menu.viewerIs(viewer)) {
            return false;
        }
        if (!featureOn(viewer)) {
            return true;
        }
        ServerPlayer target = menu.targetOrSelf(viewer);
        if (!isSamePlayer(viewer, target)) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.viewingOther"), false);
            return true;
        }
        TitleData data = data(viewer);
        if (data.equipped() == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.msg.notEquipped"), true);
            return true;
        }
        String old = data.equipped();
        data.setEquipped(null);
        save(viewer, data);
        TitleCatalog.Entry entry = TitleCatalog.byId(old);
        viewer.displayClientMessage(Component.translatable("playersuite.title.msg.unequipped",
                entry == null ? Component.literal(old) : entry.displayName()), false);
        openDetail(viewer, viewer, menu.selected(), menu.gridPage());
        return true;
    }

    // ---------------------------------------------------------------- OP 管理（详情页行内按钮）

    /** 「授予」：把当前选中的称号授予被查看玩家（adminPermission，服务端二次校验）。 */
    public static boolean adminGrantSelected(ServerPlayer viewer, TitleMenu menu) {
        return adminAct(viewer, menu, ACT_GRANT);
    }

    /** 「撤销」：撤销当前选中的称号（对方佩戴中会同时卸下）。 */
    public static boolean adminRevokeSelected(ServerPlayer viewer, TitleMenu menu) {
        return adminAct(viewer, menu, ACT_REVOKE);
    }

    /** 「强制佩戴」：让对方直接佩戴选中的（已拥有）称号。 */
    public static boolean adminForceEquip(ServerPlayer viewer, TitleMenu menu) {
        return adminAct(viewer, menu, ACT_FORCE);
    }

    private static final String ACT_GRANT = "grant";
    private static final String ACT_REVOKE = "revoke";
    private static final String ACT_FORCE = "force";

    private static boolean adminAct(ServerPlayer viewer, TitleMenu menu, String act) {
        if (menu.mode() != TitleMenu.MODE_DETAIL || !menu.viewerIs(viewer)) {
            return false;
        }
        if (!featureOn(viewer)) {
            return true;
        }
        ServerPlayer target = menu.targetOrSelf(viewer);
        boolean self = isSamePlayer(viewer, target);
        // 授予/撤销/强制佩戴都是 adminPermission；代他人操作还要求 allowManageOthers（canAdmin 内已含）；
        // 给自己授予也不豁免（防止绕过购买），只是目标退化成自己。
        if (!viewer.hasPermissions(SuiteConfig.adminPermission()) || (!self && !canAdmin(viewer))) {
            viewer.displayClientMessage(
                    Component.translatable("playersuite.msg.noPermission", SuiteConfig.adminPermission()), false);
            return true;
        }
        if (target == null || target.isRemoved()) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.targetOffline"), false);
            return true;
        }
        TitleCatalog.Entry entry = TitleCatalog.byId(menu.detailId());
        if (entry == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", menu.detailId()), true);
            return true;
        }
        return applyAdmin(viewer, target, entry, act, menu.selected(), menu.gridPage());
    }

    /** 三个管理动作的统一实现：校验 + 落盘 + sendSystemMessage 留痕 + 重开页面。 */
    private static boolean applyAdmin(ServerPlayer viewer, ServerPlayer target, TitleCatalog.Entry entry,
                                      String act, int selectedIndex, int gridPage) {
        TitleData data = data(target);
        String who = viewer.getGameProfile().getName();
        String whom = target.getGameProfile().getName();
        boolean self = isSamePlayer(viewer, target);
        switch (act) {
            case ACT_GRANT -> {
                if (data.owns(entry.id())) {
                    viewer.displayClientMessage(Component.translatable("playersuite.title.msg.targetAlreadyOwned"), true);
                    return true;
                }
                int maxOwned = SuiteConfig.titleMaxOwned();
                if (data.ownedCount() >= maxOwned) {
                    viewer.displayClientMessage(Component.translatable("playersuite.title.err.maxOwnedTarget",
                            whom, maxOwned), false);
                    return true;
                }
                data.addOwned(entry.id());
                save(target, data);
                audit(viewer, "playersuite.title.log.grant", who, whom, entry.id());
                if (!self) {
                    target.displayClientMessage(Component.translatable("playersuite.title.msg.grantedTo",
                            who, entry.displayName()), false);
                }
            }
            case ACT_REVOKE -> {
                if (!data.owns(entry.id())) {
                    viewer.displayClientMessage(Component.translatable("playersuite.title.msg.targetNotOwned"), true);
                    return true;
                }
                boolean wasEquipped = data.isEquipped(entry.id());
                data.removeOwned(entry.id());   // removeOwned 内部会连带卸下
                save(target, data);
                audit(viewer, "playersuite.title.log.revoke", who, whom, entry.id());
                if (!self) {
                    target.displayClientMessage(Component.translatable(
                            wasEquipped ? "playersuite.title.msg.revokedEquippedTo" : "playersuite.title.msg.revokedTo",
                            who, entry.displayName()), false);
                }
            }
            case ACT_FORCE -> {
                if (!data.owns(entry.id())) {
                    viewer.displayClientMessage(Component.translatable("playersuite.title.err.notOwnedTarget", whom), false);
                    return true;
                }
                data.setEquipped(entry.id());
                save(target, data);
                audit(viewer, "playersuite.title.log.force", who, whom, entry.id());
                if (!self) {
                    target.displayClientMessage(Component.translatable("playersuite.title.msg.forcedTo",
                            who, entry.displayName()), false);
                }
            }
            default -> {
                return false;
            }
        }
        ding(viewer);
        if (selectedIndex >= 0) {
            openDetail(viewer, target, indexOf(entry.id()), gridPage);
        } else {
            // 文本输入进来的没有选中页：跳到该称号所在的页
            int index = indexOf(entry.id());
            open(viewer, target, index < 0 ? gridPage : index / TitleMenu.PER_PAGE);
        }
        return true;
    }

    /** 管理动作在服务端聊天栏留痕：操作者 / 目标 / 称号 id。 */
    private static void audit(ServerPlayer viewer, String key, String operator, String target, String titleId) {
        viewer.sendSystemMessage(Component.translatable(key, operator, target, titleId));
    }

    // ---------------------------------------------------------------- 文本输入（授予/撤销/查他人）

    /**
     * InputRouter 接线入口（feature = title）。
     *
     * @param action {@link #INPUT_GRANT} / {@link #INPUT_REVOKE} / {@link #INPUT_VIEW_TARGET}
     */
    public static void onTextInput(ServerPlayer player, String action, String text) {
        if (player == null || action == null) {
            return;
        }
        if (!featureOn(player)) {
            return;
        }
        String raw = text == null ? "" : text.trim();
        switch (action) {
            case INPUT_VIEW_TARGET -> viewOther(player, raw);
            case INPUT_GRANT -> grantById(player, raw);
            case INPUT_REVOKE -> revokeById(player, raw);
            default -> {
                // 未知 action：忽略（不回显，避免被当作通用输入通道）
            }
        }
    }

    /** 管理员查看他人称号页（目标需在线）。 */
    private static void viewOther(ServerPlayer viewer, String rawName) {
        if (!canManage(viewer)) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.noManage"), false);
            return;
        }
        String name = cleanName(rawName);
        if (!PLAYER_NAME.matcher(name).matches()) {
            viewer.displayClientMessage(Component.translatable("playersuite.ui.badPlayerName"), false);
            return;
        }
        ServerPlayer target = viewer.server.getPlayerList().getPlayerByName(name);
        if (target == null || target.isRemoved()) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.playerOffline", name), false);
            return;
        }
        open(viewer, target, 0);
    }

    private static void grantById(ServerPlayer viewer, String rawId) {
        if (!viewer.hasPermissions(SuiteConfig.adminPermission())) {
            viewer.displayClientMessage(
                    Component.translatable("playersuite.msg.noPermission", SuiteConfig.adminPermission()), false);
            return;
        }
        String id = cleanId(rawId);
        TitleCatalog.Entry entry = TitleCatalog.byId(id);
        if (entry == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", id), false);
            return;
        }
        ServerPlayer target = resolveAdminTarget(viewer);
        if (target == null) {
            return;
        }
        applyAdmin(viewer, target, entry, ACT_GRANT, -1, currentPage(viewer));
    }

    private static void revokeById(ServerPlayer viewer, String rawId) {
        if (!viewer.hasPermissions(SuiteConfig.adminPermission())) {
            viewer.displayClientMessage(
                    Component.translatable("playersuite.msg.noPermission", SuiteConfig.adminPermission()), false);
            return;
        }
        String id = cleanId(rawId);
        TitleCatalog.Entry entry = TitleCatalog.byId(id);
        if (entry == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.title.err.unknownId", id), false);
            return;
        }
        ServerPlayer target = resolveAdminTarget(viewer);
        if (target == null) {
            return;
        }
        applyAdmin(viewer, target, entry, ACT_REVOKE, -1, currentPage(viewer));
    }

    /**
     * 授予/撤销对象：当前开着称号页就是该页目标，否则是自己。
     * 代他人操作需要 adminPermission + allowManageOthers，不满足时提示并返回 null。
     */
    private static ServerPlayer resolveAdminTarget(ServerPlayer viewer) {
        AbstractContainerMenu menu = viewer.containerMenu;
        if (menu instanceof TitleMenu titleMenu && titleMenu.viewerIs(viewer)) {
            ServerPlayer target = titleMenu.targetOrSelf(viewer);
            if (target != null && !target.isRemoved() && !isSamePlayer(viewer, target)) {
                if (!canAdmin(viewer)) {
                    viewer.displayClientMessage(Component.translatable("playersuite.title.err.noAdmin"), false);
                    return null;
                }
                return target;
            }
        }
        return viewer;
    }

    private static int currentPage(ServerPlayer viewer) {
        AbstractContainerMenu menu = viewer.containerMenu;
        if (menu instanceof TitleMenu titleMenu && titleMenu.viewerIs(viewer)) {
            return titleMenu.mode() == TitleMenu.MODE_DETAIL ? titleMenu.gridPage() : titleMenu.page();
        }
        return 0;
    }

    // ---------------------------------------------------------------- 对外复用

    /** 目录里第 index 个称号；越界返回 null（供其它功能复用）。 */
    public static TitleCatalog.Entry entryAt(int index) {
        return TitleCatalog.byIndex(index);
    }

    /** 称号 id 在目录里的下标；不存在返回 -1。 */
    public static int indexOf(String id) {
        if (id == null) {
            return -1;
        }
        List<TitleCatalog.Entry> list = TitleCatalog.entries();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 玩家当前佩戴且仍存在于目录中的称号；没有返回 null。 */
    private static TitleCatalog.Entry equippedEntry(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return null;
        }
        try {
            TitleData data = data(player);
            String id = data.equipped();
            if (id == null || !data.owns(id)) {
                return null;
            }
            return TitleCatalog.byId(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 当前佩戴称号按 {@code titlePrefixFormat()} 组装的前缀（供其它功能复用）。
     *
     * <p>未佩戴、功能关闭或格式为 {@code none}/空时返回 null。
     * 返回的是不带结尾空格的前缀组件；{@code %text%} 占位在这里按空文本处理。
     */
    public static Component equippedPrefix(ServerPlayer player) {
        TitleCatalog.Entry entry = equippedEntry(player);
        if (entry == null || !SuiteConfig.featureEnabled(FeatureKeys.TITLE)) {
            return null;
        }
        String fmt = normalizeFormat(SuiteConfig.titlePrefixFormat());
        if (fmt == null) {
            return null;
        }
        try {
            return renderFormat(fmt, entry, player, "").component();
        } catch (RuntimeException e) {
            return Component.literal("[" + entry.name() + "]");
        }
    }

    /**
     * 聊天注入：按格式把称号前缀拼到原版消息前面（{@code ServerChatEvent} 专用）。
     *
     * @param decorated 事件里现成的完整消息组件（含原版「玩家名: 内容」装饰）
     * @param rawText   玩家输入的原文
     * @return 新消息组件；不需要注入（未佩戴 / 格式 none / 功能关闭）时返回 null
     */
    public static Component decorateChat(ServerPlayer player, Component decorated, String rawText) {
        TitleCatalog.Entry entry = equippedEntry(player);
        if (entry == null) {
            return null;
        }
        String fmt = normalizeFormat(SuiteConfig.titlePrefixFormat());
        if (fmt == null) {
            return null;
        }
        try {
            Rendered rendered = renderFormat(fmt, entry, player, rawText == null ? "" : rawText);
            if (rendered.hasText()) {
                // 格式串自己重建了整条消息（含 %text%）
                return rendered.component();
            }
            MutableComponent out = rendered.component();
            out.append(" ");
            out.append(decorated.copy());
            return out;
        } catch (RuntimeException e) {
            // 格式串被改坏时的退化：[称号] 原版消息
            return Component.literal("[" + entry.name() + "] ").append(decorated.copy());
        }
    }

    /** 进服播报用的当前称号（配置文字是 literal，不伪装 translatable）；没有佩戴返回 null。 */
    public static Component equippedName(ServerPlayer player) {
        TitleCatalog.Entry entry = equippedEntry(player);
        return entry == null ? null : entry.displayName();
    }

    /** 可点击的「打开称号页」文本（进服提示用，参考 FeatureOpeners.hubLink 写法）。 */
    public static Component openLink() {
        return Component.translatable("playersuite.title.link")
                .withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                        "/" + SuiteConfig.hubRoot() + " title")));
    }

    // ---------------------------------------------------------------- 格式串解析

    private record Rendered(MutableComponent component, boolean hasText) {
    }

    /** 校验并归一化配置格式串；none/空返回 null（表示不拼前缀）。
     *  注意：聊天总开关 {@code titleShowInChat} 由调用方（TitleEvents）控制，
     *  不影响 {@link #equippedPrefix(ServerPlayer)} 被其它功能复用。 */
    private static String normalizeFormat(String raw) {
        String f = raw == null ? "" : raw.trim();
        if (f.isEmpty() || f.equalsIgnoreCase("none")) {
            return null;
        }
        return f;
    }

    /**
     * 占位符替换（全部容错，未知 %xx 原样保留）：
     * {@code %title% %s}=称号显示名（带配置颜色），{@code %player%}=玩家名，
     * {@code %text%}=聊天原文（出现即由格式整条重建），{@code %level%}=玩家经验等级。
     */
    private static Rendered renderFormat(String fmt, TitleCatalog.Entry entry, ServerPlayer player, String rawText) {
        MutableComponent out = Component.empty();
        boolean hasText = false;
        int i = 0;
        StringBuilder plain = new StringBuilder();
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c != '%') {
                plain.append(c);
                i++;
                continue;
            }
            String token = matchToken(fmt, i);
            if (token == null) {
                plain.append('%');
                i++;
                continue;
            }
            flush(out, plain);
            i += token.length();
            if ("%title%".equals(token) || "%s".equals(token)) {
                out.append(entry.displayName());
            } else if ("%player%".equals(token)) {
                out.append(Component.literal(player.getGameProfile().getName()));
            } else if ("%level%".equals(token)) {
                out.append(Component.literal(Integer.toString(player.experienceLevel)));
            } else {
                out.append(Component.literal(rawText));
                hasText = true;
            }
        }
        flush(out, plain);
        return new Rendered(out, hasText);
    }

    private static String matchToken(String fmt, int at) {
        for (String token : TOKENS) {
            if (fmt.startsWith(token, at)) {
                return token;
            }
        }
        return null;
    }

    private static final String[] TOKENS = {"%title%", "%player%", "%level%", "%text%", "%s"};

    private static void flush(MutableComponent out, StringBuilder plain) {
        if (plain.length() > 0) {
            out.append(plain.toString());
            plain.setLength(0);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 称号 id 输入清洗：去控制字符/格式代码，截断，小写化前先保原样（目录区分大小写）。 */
    static String cleanId(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_INPUT_LEN; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xA7) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 玩家名清洗（发送前还会用 PLAYER_NAME 正则二次校验）。 */
    static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char c = raw.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 供事件与调试用的目录快照（服务端）。 */
    public static List<TitleCatalog.Entry> catalog() {
        return TitleCatalog.entries();
    }

    private static void ding(ServerPlayer player) {
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 1.2F);
    }
}
