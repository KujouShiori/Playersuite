package dev.mcmods.playersuite.mail;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import dev.mcmods.playersuite.ui.SuiteMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 邮件业务层（服务端权威）：打开界面、发送/公告、领取附件、删除、登录投递、未读统计。
 *
 * <p>接线点（已在 {@code PlayerSuiteMod}、{@code FeatureOpeners}、{@code InputRouter} 中完成）：
 * <ul>
 *     <li>{@code FeatureOpeners.open} 里 {@code case FeatureOpeners.MAIL -> MailService.open(player, player, page);}</li>
 *     <li>{@code InputRouter.handle} 里 {@code case FeatureOpeners.MAIL -> MailService.onTextInput(sender, action, text);}</li>
 *     <li>模组构造方法里 {@code MailAttachments.register(modEventBus); MailMenus.register(modEventBus);}</li>
 * </ul>
 *
 * <p>收件箱数据只存在玩家附件 {@link MailData}；离线暂存与公告日志是全局中转表
 * {@link MailRelay}（收件人上线后搬运进其附件，见 {@link #deliverOnLogin}）。
 */
public final class MailService {

    /** openTextInput 的 action 名（收件人/标题/正文分别用 to / title / body）。 */
    public static final String INPUT_TO = "to";
    public static final String INPUT_TITLE = "title";
    public static final String INPUT_BODY = "body";
    /** 管理员「查看他人收件箱」的玩家名输入。 */
    public static final String INPUT_VIEW_TARGET = "view_target";

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private MailService() {
    }

    // ---------------------------------------------------------------- 数据访问

    public static MailData data(Player player) {
        return player.getData(MailAttachments.MAIL.get());
    }

    private static void save(Player player, MailData data) {
        player.setData(MailAttachments.MAIL.get(), data);
    }

    /** 未读邮件数（总入口角标用，由 HubMenu 调用）。 */
    public static int unread(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return 0;
        }
        try {
            return data(player).unreadCount();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** 当前功能是否开启（内部各入口统一自检）。 */
    private static boolean featureOn(ServerPlayer player) {
        if (SuiteConfig.featureEnabled(FeatureKeys.MAIL)) {
            return true;
        }
        player.displayClientMessage(Component.translatable("playersuite.mail.err.featureOff"), false);
        return false;
    }

    // ---------------------------------------------------------------- 打开收件箱（列表）

    /**
     * 以 {@code viewer} 视角打开 {@code target} 的收件箱。
     * 查看他人需要 managePermission + allowManageOthers，否则回落到自己的收件箱。
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
        boolean self = viewer.getUUID().equals(target.getUUID());
        if (!self && (!SuiteConfig.allowManageOthers() || !viewer.hasPermissions(SuiteConfig.managePermission()))) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.managePermission()), false);
            target = viewer;
            self = true;
        }
        final ServerPlayer owner = target;

        MailData data = data(owner);
        int rows = listRows();
        int total = data.size();
        int pages = Math.max(1, (total + rows - 1) / rows);
        int clamped = Mth.clamp(page, 0, pages - 1);
        int flags = flagsFor(viewer, self);
        List<MailEntry> pageMails = pageSlice(data, clamped, rows);
        int unread = data.unreadCount();
        long balance = Economy.balance(viewer);

        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> new MailMenu(containerId, inventory, viewer, owner, data,
                        MailMenu.MODE_LIST, rows, clamped, pages, -1, 0, flags, total, 0, pageMails, null, unread),
                listTitle(viewer, owner, self));
        viewer.openMenu(provider, buf -> {
            MailMenu.writeMailBuf(buf, balance, clamped, pages, (flags & MailMenu.FLAG_BROADCAST) != 0, unread,
                    MailMenu.MODE_LIST, rows, -1, flags, 0, total);
            for (MailEntry entry : pageMails) {
                buf.writeUtf(entry.title());
                buf.writeUtf(entry.senderName());
                buf.writeLong(entry.time());
                buf.writeInt(entry.isRead() ? 0 : 1);
            }
        });
    }

    /** 一页几封：几何上限与配置上限取小。 */
    public static int listRows() {
        return Math.max(1, Math.min(MailMenu.MAX_LIST_ROWS, SuiteConfig.mailPageSize()));
    }

    /** 展示顺序最新在前；返回本页邮件（可能少于 rows）。 */
    private static List<MailEntry> pageSlice(MailData data, int page, int rows) {
        List<MailEntry> out = new ArrayList<>();
        List<MailEntry> all = data.mails();
        for (int r = 0; r < rows; r++) {
            int index = all.size() - 1 - (page * rows + r);
            if (index < 0) {
                break;
            }
            out.add(all.get(index));
        }
        return out;
    }

    private static Component listTitle(ServerPlayer viewer, ServerPlayer owner, boolean self) {
        if (self) {
            return Component.translatable("playersuite.mail.inbox");
        }
        return Component.translatable("playersuite.mail.inboxOf", owner.getGameProfile().getName());
    }

    private static int flagsFor(ServerPlayer viewer, boolean self) {
        int flags = 0;
        if (!self) {
            flags |= MailMenu.FLAG_OTHER;
        }
        if (viewer.hasPermissions(SuiteConfig.managePermission())) {
            flags |= MailMenu.FLAG_BROADCAST;
            if (SuiteConfig.allowManageOthers()) {
                flags |= MailMenu.FLAG_VIEW;
            }
        }
        if (self || (SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.adminPermission()))) {
            flags |= MailMenu.FLAG_ADMIN;
        }
        if (self) {
            flags |= MailMenu.FLAG_OWNER;
        }
        return flags;
    }

    // ---------------------------------------------------------------- 详情页

    /** 打开第 index 封（附件列表下标）的详情；bodyPage 为正文分页。 */
    private static void openDetail(ServerPlayer viewer, ServerPlayer target, MailData data, int baseFlags,
                                   int index, int bodyPage, int listPage) {
        MailEntry mail = data.get(index);
        if (mail == null) {
            open(viewer, target, listPage);
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.mailGone"), true);
            return;
        }
        boolean self = viewer.getUUID().equals(target.getUUID());
        int flags = (baseFlags & ~(MailMenu.FLAG_OWNER | MailMenu.FLAG_CLAIMED))
                | (self ? MailMenu.FLAG_OWNER : 0)
                | (mail.isClaimed() ? MailMenu.FLAG_CLAIMED : 0);
        if (self && !mail.isRead()) {
            mail.markRead();
            if (!target.isRemoved()) {
                save(target, data);
            }
        }
        int attCount = mail.attachmentCount();
        int pages = estimateBodyPages(mail.body());
        int unread = data.unreadCount();
        int total = data.size();
        long balance = Economy.balance(viewer);
        final int selected = index;

        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> new MailMenu(containerId, inventory, viewer, target, data,
                        MailMenu.MODE_DETAIL, MailMenu.DETAIL_GRID_ROWS, Mth.clamp(bodyPage, 0, pages - 1), pages,
                        selected, listPage, flags, total, attCount, null, mail, unread),
                Component.translatable("playersuite.mail.detail"));
        viewer.openMenu(provider, buf -> {
            MailMenu.writeMailBuf(buf, balance, Mth.clamp(bodyPage, 0, pages - 1), pages,
                    (flags & MailMenu.FLAG_BROADCAST) != 0, unread,
                    MailMenu.MODE_DETAIL, MailMenu.DETAIL_GRID_ROWS, selected, flags, attCount, total);
            buf.writeUtf(mail.title());
            buf.writeUtf(mail.senderName());
            buf.writeUtf(mail.body());
            buf.writeLong(mail.time());
        });
    }

    /** 详情页翻页（基类上一页/下一页 → mode=DETAIL 时翻页即正文分页）。 */
    public static void reopenDetail(ServerPlayer viewer, MailMenu menu, int bodyPage) {
        if (menu.mode() != MailMenu.MODE_DETAIL || menu.data() == null) {
            return;
        }
        ServerPlayer target = menu.target() != null && !menu.target().isRemoved() ? menu.target() : viewer;
        openDetail(viewer, target, menu.data(), menu.flags(), menu.selected(), bodyPage, menu.listPage());
    }

    /** 列表第 row 行 → 详情页。 */
    public static boolean openRowAt(ServerPlayer viewer, MailMenu menu, int row) {
        if (menu.mode() != MailMenu.MODE_LIST || menu.data() == null) {
            return false;
        }
        int index = menu.data().size() - 1 - (menu.page() * menu.rows() + row);
        MailEntry mail = menu.data().get(index);
        if (mail == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.mailGone"), true);
            return true;
        }
        ServerPlayer target = menu.target() != null && !menu.target().isRemoved() ? menu.target() : viewer;
        openDetail(viewer, target, menu.data(), menu.flags(), index, 0, menu.page());
        return true;
    }

    public static void backToList(ServerPlayer viewer, MailMenu menu) {
        ServerPlayer target = menu.target() != null && !menu.target().isRemoved() ? menu.target() : viewer;
        if (!viewer.getUUID().equals(target.getUUID())
                && (!SuiteConfig.allowManageOthers() || !viewer.hasPermissions(SuiteConfig.managePermission()))) {
            target = viewer;
        }
        open(viewer, target, menu.mode() == MailMenu.MODE_DETAIL ? menu.listPage() : menu.page());
    }

    // ---------------------------------------------------------------- 写信 / 公告

    /** 列表页的「写信」「发公告」按钮。 */
    public static boolean requestCompose(ServerPlayer viewer, MailMenu menu, boolean broadcast) {
        if (menu.mode() != MailMenu.MODE_LIST) {
            return false;
        }
        if (broadcast) {
            if (!viewer.hasPermissions(SuiteConfig.managePermission())) {
                viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.managePermission()), false);
                return true;
            }
        } else if (!viewer.getUUID().equals(menu.targetUuid())) {
            // 查看他人收件箱时不代写
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.viewingOther"), false);
            return true;
        }
        openCompose(viewer, broadcast, null, "", "", "");
        return true;
    }

    private static void openCompose(ServerPlayer viewer, boolean broadcast, ItemStackHandler draft,
                                    String to, String title, String body) {
        int slots = SuiteConfig.mailAttachmentSlots();
        ItemStackHandler handler = draft != null ? draft : new ItemStackHandler(Math.max(0, slots));
        int flags = broadcast ? MailMenu.FLAG_BROADCAST : 0;
        long cost = broadcast ? 0L : SuiteConfig.mailSendCost();
        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> new MailMenu(containerId, inventory, viewer, flags,
                        handler, to, title, body, Economy.intForGui(cost)),
                Component.translatable(broadcast ? "playersuite.mail.broadcastTitle" : "playersuite.mail.compose"));
        final int attCount = handler.getSlots();
        viewer.openMenu(provider, buf -> {
            MailMenu.writeMailBuf(buf, Economy.balance(viewer), 0, 1, broadcast, 0,
                    MailMenu.MODE_COMPOSE, MailMenu.COMPOSE_GRID_ROWS, -1, flags, attCount, 0);
            buf.writeUtf(to);
            buf.writeUtf(title);
            buf.writeUtf(body);
        });
    }

    private static void reopenCompose(ServerPlayer viewer, MailMenu old) {
        old.markHandoff();
        long cost = old.isBroadcast() ? 0L : composeCost(old);
        MenuProvider provider = new SuiteMenuProvider(
                (containerId, inventory, sender) -> MailMenu.newCompose(containerId, inventory, viewer, old,
                        Economy.intForGui(cost)),
                Component.translatable(old.isBroadcast() ? "playersuite.mail.broadcastTitle" : "playersuite.mail.compose"));
        final int attCount = old.draft() == null ? 0 : old.draft().getSlots();
        viewer.openMenu(provider, buf -> {
            MailMenu.writeMailBuf(buf, Economy.balance(viewer), 0, 1, old.isBroadcast(), 0,
                    MailMenu.MODE_COMPOSE, MailMenu.COMPOSE_GRID_ROWS, -1, old.flags(), attCount, 0);
            buf.writeUtf(old.composeTextTo());
            buf.writeUtf(old.composeTextTitle());
            buf.writeUtf(old.composeTextBody());
        });
    }

    // ---------------------------------------------------------------- 文本输入

    /**
     * InputRouter 接线入口（feature = mail）。
     *
     * @param action {@link #INPUT_TO} / {@link #INPUT_TITLE} / {@link #INPUT_BODY} / {@link #INPUT_VIEW_TARGET}
     */
    public static void onTextInput(ServerPlayer player, String action, String text) {
        if (player == null || action == null) {
            return;
        }
        String raw = text == null ? "" : text;
        if (INPUT_VIEW_TARGET.equals(action)) {
            viewOtherInbox(player, raw);
            return;
        }
        if (INPUT_TO.equals(action) || INPUT_TITLE.equals(action) || INPUT_BODY.equals(action)) {
            if (player.containerMenu instanceof MailMenu menu
                    && menu.mode() == MailMenu.MODE_COMPOSE && menu.viewerIs(player)) {
                String clean = INPUT_TO.equals(action) ? cleanName(raw) : cleanText(raw, maxLenFor(action));
                if (menu.applyTextInput(action, clean)) {
                    if (clean.length() < raw.trim().length()) {
                        player.displayClientMessage(Component.translatable("playersuite.mail.msg.truncated"), true);
                    }
                    reopenCompose(player, menu);
                    return;
                }
            }
            player.displayClientMessage(Component.translatable("playersuite.mail.err.modeWrong"), true);
            return;
        }
        // 未知 action：忽略（不回显，避免被当作通用输入通道）
    }

    private static int maxLenFor(String action) {
        return INPUT_TITLE.equals(action) ? SuiteConfig.mailTitleMaxLen() : SuiteConfig.mailBodyMaxLen();
    }

    /** 管理员查看他人收件箱（目标需在线）。 */
    private static void viewOtherInbox(ServerPlayer viewer, String rawName) {
        if (!SuiteConfig.allowManageOthers() || !viewer.hasPermissions(SuiteConfig.managePermission())) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.managePermission()), false);
            return;
        }
        String name = cleanName(rawName);
        if (!PLAYER_NAME.matcher(name).matches()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.badName"), false);
            return;
        }
        ServerPlayer target = viewer.server.getPlayerList().getPlayerByName(name);
        if (target == null || target.isRemoved()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.playerOffline", name), false);
            return;
        }
        open(viewer, target, 0);
    }

    // ---------------------------------------------------------------- 发送

    /** 写信页费用：基础费 + 每个附件费（溢出饱和到 Long.MAX，永远付不起即拒绝）。 */
    public static long composeCost(MailMenu menu) {
        if (menu.isBroadcast()) {
            return 0L;
        }
        int filled = countFilled(menu.draft());
        return addCost(SuiteConfig.mailSendCost(), mulCost(SuiteConfig.mailAttachmentCost(), filled));
    }

    private static int countFilled(ItemStackHandler handler) {
        if (handler == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 冷却剩余秒数（0 表示可发送）。 */
    public static int cooldownRemainingSeconds(ServerPlayer sender) {
        int cd = SuiteConfig.mailSendCooldownSeconds();
        if (cd <= 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - data(sender).lastSendAt();
        if (elapsed >= cd * 1000L) {
            return 0;
        }
        return (int) Math.min(cd, Math.max(1L, (cd * 1000L - elapsed + 999L) / 1000L));
    }

    /** 发送邮件 / 发布公告（服务端唯一入口，onAction 已保证 viewer 与 menu 匹配）。 */
    public static boolean send(ServerPlayer viewer, MailMenu menu) {
        if (menu.mode() != MailMenu.MODE_COMPOSE || menu.isSent()) {
            return false;
        }
        if (!featureOn(viewer)) {
            return true;
        }
        String title = menu.composeTextTitle();
        String body = menu.composeTextBody();
        if (title.isBlank()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.noTitle"), true);
            return true;
        }
        int remaining = cooldownRemainingSeconds(viewer);
        if (remaining > 0) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.cooldown", remaining), true);
            return true;
        }
        return menu.isBroadcast()
                ? sendBroadcast(viewer, menu, title, body)
                : sendPrivate(viewer, menu, title, body);
    }

    private static boolean sendPrivate(ServerPlayer viewer, MailMenu menu, String title, String body) {
        String name = menu.composeTextTo();
        if (name.isBlank()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.noRecipient"), true);
            return true;
        }
        if (!PLAYER_NAME.matcher(name).matches()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.badName"), false);
            return true;
        }
        MinecraftServer server = viewer.server;
        ServerPlayer recipient = server.getPlayerList().getPlayerByName(name);
        if (recipient != null && recipient.isRemoved()) {
            recipient = null;
        }
        UUID recipientUuid = null;
        String recipientName = name;
        MailData recipientData = null;
        if (recipient != null) {
            recipientUuid = recipient.getUUID();
            recipientName = recipient.getGameProfile().getName();
            recipientData = data(recipient);
        } else {
            var cached = server.getProfileCache().get(name);
            if (cached.isEmpty()) {
                viewer.displayClientMessage(Component.translatable("playersuite.mail.err.noRecipient", name), false);
                return true;
            }
            recipientUuid = cached.get().getId();
            recipientName = cached.get().getName() == null ? name : cached.get().getName();
            if (MailRelay.of(server).pendingCount(recipientUuid) >= MailRelay.PENDING_CAP) {
                viewer.displayClientMessage(Component.translatable("playersuite.mail.err.boxFull",
                        MailRelay.PENDING_CAP), false);
                return true;
            }
        }
        if (recipientData != null && !recipientData.canReceive()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.boxFull",
                    MailData.capacity()), false);
            return true;
        }

        // 先数附件、再算钱、扣钱成功后才消耗草稿（发货失败一律在扣钱前拒绝）
        int filled = countFilled(menu.draft());
        long cost = addCost(SuiteConfig.mailSendCost(), mulCost(SuiteConfig.mailAttachmentCost(), filled));
        if (cost > SuiteConfig.maxBalance()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.money",
                    Economy.label(SuiteConfig.maxBalance())), false);
            return true;
        }
        if (!Economy.withdraw(viewer, cost, "mail_send")) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.money",
                    Economy.label(cost)), false);
            return true;
        }

        List<ItemStack> attachments = takeDraft(menu);
        long now = System.currentTimeMillis();
        MailData senderData = data(viewer);
        senderData.setLastSendAt(now);
        save(viewer, senderData);
        menu.markSent();

        if (recipient != null && recipientData != null) {
            MailEntry mail = new MailEntry(recipientData.nextMailId(), viewer.getUUID(),
                    viewer.getGameProfile().getName(), title, body, now, attachments, false);
            if (!recipientData.addMail(mail)) {
                // 理论上不可达（同一 tick 内已校验容量）；保底退款退件，绝不吞东西
                rollback(viewer, cost, attachments, menu);
                viewer.displayClientMessage(Component.translatable("playersuite.mail.err.boxFull",
                        MailData.capacity()), false);
                return true;
            }
            save(recipient, recipientData);
            if (recipient != viewer) {
                recipient.displayClientMessage(
                        Component.translatable("playersuite.mail.notify", viewer.getGameProfile().getName())
                                .append(Component.literal(" ")).append(openLink()), false);
                recipient.displayClientMessage(Component.translatable("playersuite.mail.notifyUnread",
                        recipientData.unreadCount()), true);
            }
        } else {
            MailEntry mail = new MailEntry(now, viewer.getUUID(), viewer.getGameProfile().getName(),
                    title, body, now, attachments, false);
            CompoundTag tag = mail.writeTo(new CompoundTag(), server.registryAccess());
            if (!MailRelay.of(server).addPending(recipientUuid, tag)) {
                rollback(viewer, cost, attachments, menu);
                viewer.displayClientMessage(Component.translatable("playersuite.mail.err.boxFull",
                        MailRelay.PENDING_CAP), false);
                return true;
            }
        }
        viewer.displayClientMessage(cost > 0L
                ? Component.translatable("playersuite.mail.msg.sentPaid", recipientName, Economy.label(cost))
                : Component.translatable("playersuite.mail.msg.sent", recipientName), false);
        playDing(viewer);
        open(viewer, viewer, 0);
        return true;
    }

    private static boolean sendBroadcast(ServerPlayer viewer, MailMenu menu, String title, String body) {
        if (!viewer.hasPermissions(SuiteConfig.managePermission())) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.managePermission()), false);
            return true;
        }
        long now = System.currentTimeMillis();
        MailData senderData = data(viewer);
        senderData.setLastSendAt(now);
        save(viewer, senderData);
        menu.markSent();

        MailRelay relay = MailRelay.of(viewer.server);
        long seq = relay.addBroadcast(title, body, now);
        int delivered = 0;
        for (ServerPlayer online : viewer.server.getPlayerList().getPlayers()) {
            MailData box = data(online);
            if (box.canReceive()) {
                box.addMail(new MailEntry(MailData.capacity() + seq, null, "", title, body, now, null, false));
                save(online, box);
                online.displayClientMessage(Component.translatable("playersuite.mail.notifyUnread",
                        box.unreadCount()), true);
                delivered++;
            }
            box.advanceBroadcastSeq(seq);
        }
        viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.broadcastSent", delivered), false);
        playDing(viewer);
        open(viewer, viewer, 0);
        return true;
    }

    /** 扣钱后投递失败的回滚：退款 + 附件塞回草稿（menu 仍在打开状态）。 */
    private static void rollback(ServerPlayer viewer, long cost, List<ItemStack> attachments, MailMenu menu) {
        if (cost > 0L) {
            Economy.deposit(viewer, cost, "mail_send_refund");
        }
        var draft = menu.draft();
        for (ItemStack stack : attachments) {
            ItemStack rest = stack;
            if (draft != null) {
                for (int i = 0; i < draft.getSlots() && !rest.isEmpty(); i++) {
                    // insertItem 返回未插入的剩余堆栈（全接受时返回 EMPTY）
                    rest = draft.insertItem(i, rest, false);
                }
            }
            if (!rest.isEmpty()) {
                viewer.getInventory().add(rest);
                if (!rest.isEmpty()) {
                    viewer.drop(rest, false);
                }
            }
        }
        attachments.clear();
    }

    /** 从草稿 handler 取出全部附件（消耗动作，扣钱成功后调用）。 */
    private static List<ItemStack> takeDraft(MailMenu menu) {
        List<ItemStack> out = new ArrayList<>();
        var draft = menu.draft();
        if (draft == null) {
            return out;
        }
        for (int i = 0; i < draft.getSlots(); i++) {
            ItemStack stack = draft.extractItem(i, Integer.MAX_VALUE, false);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 领取附件

    public static boolean claimAll(ServerPlayer viewer, MailMenu menu) {
        if (menu.mode() != MailMenu.MODE_DETAIL || menu.data() == null) {
            return false;
        }
        if (!viewer.getUUID().equals(menu.targetUuid())) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.onlyOwner"), false);
            return true;
        }
        MailEntry mail = menu.currentDetail();
        if (mail == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.mailGone"), true);
            backToList(viewer, menu);
            return true;
        }
        if (mail.isClaimed()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.alreadyClaimed"), true);
            return true;
        }
        List<ItemStack> stacks = mail.attachmentList();
        if (stacks.isEmpty()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.noAttachments"), true);
            return true;
        }
        if (!fitsInInventory(viewer, stacks)) {
            // 背包放不下：保持未领取，物品留在邮件里
            viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.invFull"), false);
            return true;
        }
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            viewer.getInventory().add(copy);
            if (!copy.isEmpty()) {
                // 理论上模拟通过后不会有余量，保底掉在脚下，绝不吞物品
                viewer.drop(copy, false);
            }
        }
        mail.markClaimed();
        save(viewer, menu.data());
        viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.claimed", stacks.size()), false);
        playDing(viewer);
        ServerPlayer target = menu.target() != null && !menu.target().isRemoved() ? menu.target() : viewer;
        openDetail(viewer, target, menu.data(), menu.flags(), menu.selected(), menu.page(), menu.listPage());
        return true;
    }

    /** 背包能否装下全部这些堆栈（在副本上模拟原版放物规则，不动真实背包）。 */
    static boolean fitsInInventory(Player player, List<ItemStack> stacks) {
        List<ItemStack> sim = new ArrayList<>(player.getInventory().items.size());
        for (ItemStack existing : player.getInventory().items) {
            sim.add(existing.copy());
        }
        for (ItemStack stack : stacks) {
            if (!placeSimulated(sim, stack.copy())) {
                return false;
            }
        }
        return true;
    }

    private static boolean placeSimulated(List<ItemStack> sim, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (stack.isDamaged()) {
            for (int i = 0; i < sim.size(); i++) {
                if (sim.get(i).isEmpty()) {
                    sim.set(i, stack);
                    return true;
                }
            }
            return false;
        }
        if (stack.isStackable()) {
            for (int i = 0; i < sim.size() && !stack.isEmpty(); i++) {
                ItemStack target = sim.get(i);
                if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) {
                    continue;
                }
                int space = target.getMaxStackSize() - target.getCount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, stack.getCount());
                target.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < sim.size() && !stack.isEmpty(); i++) {
            if (sim.get(i).isEmpty()) {
                int put = Math.min(stack.getMaxStackSize(), stack.getCount());
                sim.set(i, stack.split(put));
            }
        }
        return stack.isEmpty();
    }

    // ---------------------------------------------------------------- 删除 / 清空

    /** 列表第 row 行的删除按钮（本人随意；删他人邮件必须 adminPermission，服务端二次校验）。 */
    public static boolean deleteRowAt(ServerPlayer viewer, MailMenu menu, int row) {
        if (menu.mode() != MailMenu.MODE_LIST || menu.data() == null) {
            return false;
        }
        ServerPlayer target = menu.target();
        boolean self = viewer.getUUID().equals(menu.targetUuid());
        if (!self && (!SuiteConfig.allowManageOthers()
                || !viewer.hasPermissions(SuiteConfig.adminPermission()))) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.adminPermission()), false);
            return true;
        }
        if (target == null || target.isRemoved()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.targetOffline"), false);
            open(viewer, viewer, 0);
            return true;
        }
        MailData data = menu.data();
        int index = data.size() - 1 - (menu.page() * menu.rows() + row);
        MailEntry mail = data.remove(index);
        if (mail == null) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.mailGone"), true);
            return true;
        }
        refundAttachments(target, mail.attachmentList());
        save(target, data);
        viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.deleted"), true);
        open(viewer, target, menu.page());
        return true;
    }

    /** 清空收件箱（本人；他人需 adminPermission + allowManageOthers，服务端二次校验）。 */
    public static boolean clearInbox(ServerPlayer viewer, MailMenu menu) {
        if (menu.mode() != MailMenu.MODE_LIST || menu.data() == null) {
            return false;
        }
        ServerPlayer target = menu.target();
        boolean self = viewer.getUUID().equals(menu.targetUuid());
        if (!self && (!SuiteConfig.allowManageOthers()
                || !viewer.hasPermissions(SuiteConfig.adminPermission()))) {
            viewer.displayClientMessage(Component.translatable("playersuite.msg.noPermission", SuiteConfig.adminPermission()), false);
            return true;
        }
        if (target == null || target.isRemoved()) {
            viewer.displayClientMessage(Component.translatable("playersuite.mail.err.targetOffline"), false);
            return true;
        }
        MailData data = menu.data();
        int removed = data.size();
        List<ItemStack> loose = new ArrayList<>();
        for (MailEntry mail : data.mails()) {
            loose.addAll(mail.attachmentList());
        }
        data.clearAll();
        save(target, data);
        refundAttachments(target, loose);
        viewer.displayClientMessage(Component.translatable("playersuite.mail.msg.cleared", removed), false);
        open(viewer, target, 0);
        return true;
    }

    /** 删信退还附件：背包放不下的掉在玩家脚下（绝不静默吞物品）。 */
    private static void refundAttachments(Player owner, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            owner.getInventory().add(copy);
            if (!copy.isEmpty()) {
                owner.drop(copy, false);
            }
        }
    }

    // ---------------------------------------------------------------- 登录投递

    /**
     * 登录时把离线暂存私信与错过的公告搬进玩家附件。
     *
     * @return 搬运后是否有数据变化（调用方不需要再存）
     */
    public static boolean deliverOnLogin(ServerPlayer player) {
        MailData box = data(player);
        boolean changed = false;
        try {
            MailRelay relay = MailRelay.of(player.server);
            HolderLookup.Provider provider = player.server.registryAccess();
            UUID uuid = player.getUUID();

            List<CompoundTag> pending = relay.takePending(uuid);
            if (!pending.isEmpty()) {
                int used = 0;
                for (CompoundTag tag : pending) {
                    if (!box.canReceive()) {
                        break;
                    }
                    box.addMail(MailEntry.readFrom(tag, provider));
                    changed = true;
                    used++;
                }
                if (used < pending.size()) {
                    relay.returnPending(uuid, pending.subList(used, pending.size()));
                }
            }

            long latest = relay.latestSeq();
            if (latest > box.lastBroadcastSeq()) {
                for (MailRelay.Broadcast broadcast : relay.broadcastList()) {
                    if (broadcast.seq() <= box.lastBroadcastSeq()) {
                        continue;
                    }
                    if (!box.canReceive()) {
                        break;
                    }
                    box.addMail(new MailEntry(broadcast.seq(), null, "", broadcast.title(),
                            broadcast.body(), broadcast.time(), null, false));
                    changed = true;
                }
                // 游标直接推到最新：收件箱已满而错过的公告视为放弃（这是有意取舍：公告不做补投）
                box.advanceBroadcastSeq(latest);
                changed = true;
            }
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("投递 {} 的暂存邮件失败", player.getGameProfile().getName(), e);
        }
        if (changed) {
            save(player, box);
        }
        return changed;
    }

    // ---------------------------------------------------------------- 提示与工具

    /** 可点击的「打开邮箱」文本（登录提醒、新邮件通知用；参考 FeatureOpeners.hubLink 写法）。 */
    public static Component openLink() {
        return Component.translatable("playersuite.mail.link")
                .withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND, "/" + SuiteConfig.hubRoot() + " mail")));
    }

    /** 正文分页估算（服务端只能估算字体，最终以客户端本地换行为准）。 */
    static int estimateBodyPages(String body) {
        if (body == null || body.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int width = 0;
        for (int i = 0; i < body.length(); i++) {
            int c = body.charAt(i);
            // 故意宽估（ASCII 7px / 其它 11px），保证服务端页数 ≥ 客户端实际页数，不会翻不到正文
            int w = c < 0x7F ? 7 : 11;
            if (width + w > MailMenu.DETAIL_BODY_W) {
                lines++;
                width = w;
            } else {
                width += w;
            }
        }
        return Math.max(1, (lines + MailMenu.DETAIL_LINES_PER_PAGE - 1) / MailMenu.DETAIL_LINES_PER_PAGE);
    }

    /** 清洗文本：去控制字符与格式代码，裁剪长度，去首尾空白。 */
    public static String cleanText(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), Math.max(16, maxLen * 4)));
        int count = 0;
        int limit = Math.max(1, maxLen);
        for (int i = 0; i < raw.length() && count < limit; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F || cp == 0xA7) {
                continue;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString().trim();
    }

    /** 玩家名清洗（发送时还会用 PLAYER_NAME 正则二次校验）。 */
    public static String cleanName(String raw) {
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

    private static long addCost(long a, long b) {
        if (a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private static long mulCost(long unit, int count) {
        if (unit <= 0L || count <= 0) {
            return 0L;
        }
        if (count > Long.MAX_VALUE / unit) {
            return Long.MAX_VALUE;
        }
        return unit * count;
    }

    private static void playDing(ServerPlayer player) {
        player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 1.2F);
    }
}
