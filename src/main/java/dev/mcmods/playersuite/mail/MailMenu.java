package dev.mcmods.playersuite.mail;

import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.DisplaySlots;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 邮件容器：一个 Menu 承载三种页面，{@code mode} 通过 {@link PageMenu#writeFull} 的
 * 第一个 sub int 下发（客户端构造函数按同一顺序读回）。
 *
 * <ul>
 *     <li>{@link #MODE_LIST}：收件箱分页列表，每封邮件占 2 格（36 像素）高，
 *         左侧第一附件图标（只读展示槽），中间标题/发件人/日期（在 Screen 里绘制），
 *         右侧「打开」「删除」按钮（BTN_SELECT_BASE / BTN_DELETE_BASE + 行号）；</li>
 *     <li>{@link #MODE_DETAIL}：正文自动换行分页（翻页按钮复用基类的上一页/下一页）+
 *         附件展示槽 + 领取/返回按钮；</li>
 *     <li>{@link #MODE_COMPOSE}：写信/公告。草稿物品存放在服务端 Menu 实例的
 *         {@link ItemStackHandler} 里（不落盘），界面关闭时退回玩家背包。</li>
 * </ul>
 *
 * <p>sub int 顺序（服务端写入与客户端读取必须一致）：
 * mode, rows, selected, flags, attCount, total；
 * 之后按 mode 追加：LIST 每行 (title, sender, time(long), unread(int))；
 * DETAIL (title, sender, body, time(long))；COMPOSE (to, title, body)。
 */
public class MailMenu extends PageMenu {

    // ---------------------------------------------------------------- mode / flags

    public static final int MODE_LIST = 0;
    public static final int MODE_DETAIL = 1;
    public static final int MODE_COMPOSE = 2;

    /** 本类自己追加的 sub int 个数。 */
    public static final int SUB_INTS = 6;

    /**
     * 供 {@link MailService} 写 openMenu 附加数据：writeFull 是 {@code PageMenu} 的
     * protected 静态方法，只能在子类内部转发。
     */
    public static void writeMailBuf(RegistryFriendlyByteBuf buf, long balance, int page, int pages,
                                    boolean manage, int status, int... subData) {
        writeFull(buf, balance, page, pages, manage, status, subData);
    }

    /** 查看的是别人的收件箱。 */
    public static final int FLAG_OTHER = 1;
    /** 允许发全服公告（managePermission 达标，写进公共头 manage 字段）。 */
    public static final int FLAG_BROADCAST = 2;
    /** 允许破坏性管理：删指定邮件 / 清空收件箱（adminPermission 达标）。 */
    public static final int FLAG_ADMIN = 4;
    /** 详情页：当前查看者就是收件人本人（可以领取附件/标已读）。 */
    public static final int FLAG_OWNER = 8;
    /** 详情页：附件已领取过。 */
    public static final int FLAG_CLAIMED = 16;
    /** 允许“查看他人收件箱”（managePermission + allowManageOthers，服务端算好下发）。 */
    public static final int FLAG_VIEW = 32;

    // ---------------------------------------------------------------- 按钮编号

    public static final int BTN_COMPOSE = BTN_CUSTOM;          // 10
    public static final int BTN_BROADCAST = BTN_CUSTOM + 1;    // 11
    public static final int BTN_SEND = BTN_CUSTOM + 2;         // 12
    public static final int BTN_CLAIM_ALL = BTN_CUSTOM + 3;    // 13
    public static final int BTN_BACK_TO_LIST = BTN_CUSTOM + 4; // 14
    public static final int BTN_CLEAR_INBOX = BTN_CUSTOM + 5;  // 15
    /** 第 r 行的「打开」= BTN_SELECT_BASE + r。 */
    public static final int BTN_SELECT_BASE = BTN_CUSTOM + 10; // 20..23
    /** 第 r 行的「删除」= BTN_DELETE_BASE + r。 */
    public static final int BTN_DELETE_BASE = BTN_CUSTOM + 20; // 30..33

    // ---------------------------------------------------------------- 几何常量（与 MailScreen 共用）

    /** 列表一页最多几封：36 像素/行的几何上限；实际取 min(此值, mailPageSize())。 */
    public static final int MAX_LIST_ROWS = 4;
    public static final int LIST_ROW_PITCH = 36;
    public static final int LIST_ICON_X = Layout.GRID_LEFT;   // 8
    public static final int TEXT_X = 33;                       // 列表行文字起始 x
    public static final int BTN_OPEN_X = 118;
    public static final int BTN_DEL_X = 145;
    public static final int BTN_ROW_W = 26;
    public static final int BTN_ROW_H = 16;

    public static final int DETAIL_GRID_ROWS = 6;
    public static final int DETAIL_TITLE_Y = 22;
    public static final int DETAIL_FROM_Y = 33;
    public static final int DETAIL_BODY_Y = 44;
    public static final int DETAIL_BODY_LINE_H = 10;
    public static final int DETAIL_BODY_W = 160;
    public static final int DETAIL_LINES_PER_PAGE = 5;
    public static final int DETAIL_ATT_LABEL_Y = 98;
    public static final int DETAIL_ATT_Y = 110;

    public static final int COMPOSE_GRID_ROWS = 3;
    public static final int COMPOSE_TO_Y = 23;
    public static final int COMPOSE_TITLE_Y = 32;
    public static final int COMPOSE_BODY_Y = 41;
    public static final int COMPOSE_DRAFT_Y = Layout.contentTop() + 2 * Layout.PITCH; // 57

    // ---------------------------------------------------------------- 字符串读取上限

    private static final int LIMIT_NAME = 64;
    private static final int LIMIT_TITLE = 256;
    private static final int LIMIT_BODY = 8192;

    // ---------------------------------------------------------------- 通用状态

    private final int mode;
    private final int rows;
    private final int selected;
    private final int listPage;
    private final int flags;
    private final int attCount;
    private final int total;

    /** 服务端：目标玩家与其邮箱数据；客户端为 null。 */
    private final ServerPlayer target;
    private final UUID targetUuid;
    private final MailData data;
    /** 服务端写信草稿（不落盘）；客户端为镜像空实现。 */
    private final ItemStackHandler draft;

    /** 已发送标记：连点防重（置位后本实例不再接受发送）。 */
    private boolean sent;
    /** 重建菜单时旧实例不再退草稿物品（物品随同一个 handler 移交到新实例）。 */
    private boolean handoff;

    // ---------------------------------------------------------------- 客户端渲染数据

    private final List<RowView> clientRows = new ArrayList<>();
    private String detailTitle = "";
    private String detailSender = "";
    private String detailBody = "";
    private long detailTime;
    private String composeTo = "";
    private String composeTitle = "";
    private String composeBody = "";

    /** 列表一行在客户端的显示快照。 */
    public record RowView(String title, String sender, long time, boolean unread) {
    }

    // ---------------------------------------------------------------- 构造

    /** 客户端构造：(containerId, inventory, extraData)，由 IMenuTypeExtension.create 反射匹配。 */
    public MailMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(MailMenus.MAIL_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        int[] sub = readSubData(buf, SUB_INTS);
        this.mode = sub[0];
        this.rows = Math.max(0, sub[1]);
        this.selected = sub[2];
        this.flags = sub[3];
        this.attCount = Math.max(0, Math.min(9, sub[4]));
        this.total = Math.max(0, sub[5]);
        this.listPage = 0;
        this.target = null;
        this.targetUuid = null;
        this.data = null;
        this.draft = null;

        switch (mode) {
            case MODE_LIST -> {
                // 末页可能不满 rows 封：只读/只建实际存在的那几行（与服务端写入严格一致）
                int onPage = Math.max(0, Math.min(rows, total - page() * rows));
                for (int r = 0; r < onPage; r++) {
                    String title = buf.readUtf(LIMIT_TITLE);
                    String sender = buf.readUtf(LIMIT_NAME);
                    long time = buf.readLong();
                    boolean unread = buf.readInt() != 0;
                    clientRows.add(new RowView(title, sender, time, unread));
                    addSlot(DisplaySlots.empty(LIST_ICON_X, listIconY(r)));
                }
            }
            case MODE_DETAIL -> {
                detailTitle = buf.readUtf(LIMIT_TITLE);
                detailSender = buf.readUtf(LIMIT_NAME);
                detailBody = buf.readUtf(LIMIT_BODY);
                detailTime = buf.readLong();
                for (int i = 0; i < attCount; i++) {
                    addSlot(DisplaySlots.empty(Layout.slotX(i), DETAIL_ATT_Y));
                }
            }
            case MODE_COMPOSE -> {
                composeTo = buf.readUtf(LIMIT_NAME);
                composeTitle = buf.readUtf(LIMIT_TITLE);
                composeBody = buf.readUtf(LIMIT_BODY);
                ItemStackHandler clientDraft = new ItemStackHandler(attCount);
                for (int i = 0; i < attCount; i++) {
                    addSlot(new SlotItemHandler(clientDraft, i, Layout.slotX(i), COMPOSE_DRAFT_Y));
                }
                addPlayerInventory(inventory, COMPOSE_GRID_ROWS);
            }
            default -> {
                // 未知 mode：不建槽位，界面按空页渲染
            }
        }
    }

    /** 服务端构造（列表 / 详情）。 */
    MailMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer target, MailData data,
             int mode, int rows, int page, int pages, int selected, int listPage, int flags,
             int total, int attCount, List<MailEntry> pageMails, MailEntry detail, int unread) {
        super(MailMenus.MAIL_MENU.get(), containerId, inventory, viewer, page, pages,
                (flags & FLAG_BROADCAST) != 0);
        this.mode = mode;
        this.rows = Math.max(0, rows);
        this.selected = selected;
        this.listPage = listPage;
        this.flags = flags;
        this.attCount = Math.max(0, attCount);
        this.total = Math.max(0, total);
        this.target = target;
        this.targetUuid = target.getUUID();
        this.data = data;
        this.draft = null;
        setStatus(Math.max(0, unread));
        if (mode == MODE_LIST && pageMails != null) {
            for (int r = 0; r < pageMails.size() && r < this.rows; r++) {
                MailEntry entry = pageMails.get(r);
                addSlot(DisplaySlots.of(entry.attachmentForDisplay(0), LIST_ICON_X, listIconY(r)));
            }
        } else if (mode == MODE_DETAIL && detail != null) {
            for (int i = 0; i < this.attCount; i++) {
                addSlot(DisplaySlots.of(detail.attachmentForDisplay(i), Layout.slotX(i), DETAIL_ATT_Y));
            }
        }
    }

    /** 服务端构造（写信）。 */
    MailMenu(int containerId, Inventory inventory, ServerPlayer viewer, int flags,
             ItemStackHandler draft, String to, String mailTitle, String mailBody, int costGui) {
        super(MailMenus.MAIL_MENU.get(), containerId, inventory, viewer, 0, 1,
                (flags & FLAG_BROADCAST) != 0);
        this.mode = MODE_COMPOSE;
        this.rows = COMPOSE_GRID_ROWS;
        this.selected = -1;
        this.listPage = 0;
        this.flags = flags;
        this.attCount = draft == null ? 0 : Math.max(0, draft.getSlots());
        this.total = 0;
        this.target = viewer;
        this.targetUuid = viewer.getUUID();
        this.data = null;
        this.draft = draft;
        this.composeTo = to == null ? "" : to;
        this.composeTitle = mailTitle == null ? "" : mailTitle;
        this.composeBody = mailBody == null ? "" : mailBody;
        setExtra(Math.max(0, costGui));
        for (int i = 0; i < attCount; i++) {
            addSlot(new SlotItemHandler(draft, i, Layout.slotX(i), COMPOSE_DRAFT_Y));
        }
        addPlayerInventory(inventory, COMPOSE_GRID_ROWS);
    }

    /**
     * 文本输入后重建写信菜单：草稿 handler / 已填字段原样移交，物品不落地。
     * 调用方必须先 {@link #markHandoff()} 本实例，否则旧实例关闭时会把草稿退回背包。
     */
    public static MailMenu newCompose(int containerId, Inventory inventory, ServerPlayer viewer,
                                      MailMenu old, int costGui) {
        return new MailMenu(containerId, inventory, viewer, old.flags, old.draft,
                old.composeTo, old.composeTitle, old.composeBody, costGui);
    }

    // ---------------------------------------------------------------- 列表坐标

    /** 列表第 row 行图标的 y（面板相对坐标）。 */
    public static int listIconY(int row) {
        return Layout.contentTop() + row * LIST_ROW_PITCH;
    }

    /** 本模式下的内容行数（PageScreen 构造用）。 */
    public int contentRows() {
        return switch (mode) {
            case MODE_DETAIL -> DETAIL_GRID_ROWS;
            case MODE_COMPOSE -> COMPOSE_GRID_ROWS;
            default -> Math.max(1, rows) * 2;
        };
    }

    // ---------------------------------------------------------------- 按钮分发（全部服务端二次校验）

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        return switch (buttonId) {
            case BTN_COMPOSE -> MailService.requestCompose(viewer, this, false);
            case BTN_BROADCAST -> MailService.requestCompose(viewer, this, true);
            case BTN_SEND -> MailService.send(viewer, this);
            case BTN_CLAIM_ALL -> MailService.claimAll(viewer, this);
            case BTN_BACK_TO_LIST -> {
                MailService.backToList(viewer, this);
                yield true;
            }
            case BTN_CLEAR_INBOX -> MailService.clearInbox(viewer, this);
            default -> {
                if (buttonId >= BTN_SELECT_BASE && buttonId < BTN_SELECT_BASE + MAX_LIST_ROWS) {
                    yield MailService.openRowAt(viewer, this, buttonId - BTN_SELECT_BASE);
                }
                if (buttonId >= BTN_DELETE_BASE && buttonId < BTN_DELETE_BASE + MAX_LIST_ROWS) {
                    yield MailService.deleteRowAt(viewer, this, buttonId - BTN_DELETE_BASE);
                }
                yield false;
            }
        };
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        if (mode == MODE_DETAIL) {
            MailService.reopenDetail(viewer, this, page);
        } else if (mode != MODE_COMPOSE) {
            MailService.open(viewer, targetOrFallback(viewer), page);
        }
    }

    // ---------------------------------------------------------------- 文本输入（服务端应用）

    /**
     * 处理 openTextInput 提交的文本：清洗控制字符 + 服务端截断（客户端输入完全不可信）。
     * 只有写信页接受 to / title / body。
     *
     * @return 是否成功写入对应字段
     */
    public boolean applyTextInput(String action, String text) {
        if (mode != MODE_COMPOSE) {
            return false;
        }
        switch (action) {
            case MailService.INPUT_TO -> {
                composeTo = MailService.cleanName(text);
                return true;
            }
            case MailService.INPUT_TITLE -> {
                composeTitle = MailService.cleanText(text, SuiteConfig.mailTitleMaxLen());
                return true;
            }
            case MailService.INPUT_BODY -> {
                composeBody = MailService.cleanText(text, SuiteConfig.mailBodyMaxLen());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- 状态存取

    public int mode() {
        return mode;
    }

    public int rows() {
        return rows;
    }

    public int selected() {
        return selected;
    }

    public int listPage() {
        return listPage;
    }

    public int flags() {
        return flags;
    }

    /** 详情页/写信页展示槽数量（附件或草稿格）。 */
    public int displayAttachmentCount() {
        return attCount;
    }

    public int attCount() {
        return attCount;
    }

    public int total() {
        return total;
    }

    public boolean isBroadcast() {
        return mode == MODE_COMPOSE && (flags & FLAG_BROADCAST) != 0;
    }

    public boolean isSent() {
        return sent;
    }

    public void markSent() {
        this.sent = true;
    }

    public void markHandoff() {
        this.handoff = true;
    }

    /** 服务端：详情页当前邮件（越界返回 null）。 */
    public MailEntry currentDetail() {
        return data == null ? null : data.get(selected);
    }

    public ServerPlayer target() {
        return target;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public MailData data() {
        return data;
    }

    public ItemStackHandler draft() {
        return draft;
    }

    public boolean viewerIs(Player player) {
        return viewer != null && viewer == player;
    }

    // ---------------------------------------------------------------- 客户端渲染数据

    public List<RowView> clientRows() {
        return clientRows;
    }

    public String detailTitle() {
        return detailTitle;
    }

    public String detailSender() {
        return detailSender;
    }

    public String detailBody() {
        return detailBody;
    }

    public long detailTime() {
        return detailTime;
    }

    public String composeTextTo() {
        return composeTo;
    }

    public String composeTextTitle() {
        return composeTitle;
    }

    public String composeTextBody() {
        return composeBody;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 1.21.1 的 ServerPlayer#tick 每刻都会调用打开中容器的 broadcastChanges()，
     * 借这个时机刷新写信页的「费用/冷却」提示（ContainerData 会自动把变化同步给客户端）。
     */
    @Override
    public void broadcastChanges() {
        if (viewer != null && mode == MODE_COMPOSE && draft != null) {
            long cost = MailService.composeCost(this);
            setExtra(Economy.intForGui(cost));
            setStatus(MailService.cooldownRemainingSeconds(viewer));
        }
        super.broadcastChanges();
    }

    private ServerPlayer targetOrFallback(ServerPlayer viewer) {
        if (target != null && !target.isRemoved() && target.server == viewer.server) {
            return target;
        }
        return viewer;
    }

    // ---------------------------------------------------------------- 关闭退回草稿

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (viewer != null && mode == MODE_COMPOSE && !handoff && draft != null && !player.level().isClientSide) {
            for (int i = 0; i < draft.getSlots(); i++) {
                ItemStack stack = draft.extractItem(i, Integer.MAX_VALUE, false);
                if (stack.isEmpty()) {
                    continue;
                }
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    player.drop(stack, false);
                }
            }
        }
    }
}
