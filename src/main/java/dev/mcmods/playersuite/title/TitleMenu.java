package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.DisplaySlots;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 称号容器：一个 Menu 承载两种页面，{@code mode} 通过附加数据的第一个 sub int 下发。
 *
 * <ul>
 *     <li>{@link #MODE_GRID}：称号墙。每页 {@value #PER_PAGE} 个（每行 {@value #COLS} 个
 *         {@link DisplaySlots} 图标，共 {@value #GRID_ROWS} 行），页数 = ceil(目录数 / 每页)；
 *         已拥有的图标右上角有小绿点，正在佩戴的图标用 {@code Theme.highlight} 描边；
 *         每个图标下方一个小「查」按钮（{@link #BTN_INFO_BASE} + 行号）进入详情。</li>
 *     <li>{@link #MODE_DETAIL}：详情。中央图标 + 显示名/价格/是否已拥有/是否佩戴，
 *         本人页按钮为购买/佩戴/卸下，管理员看他人页按钮为授予/撤销/强制佩戴。</li>
 * </ul>
 *
 * <p>sub int 顺序（服务端写入与客户端读取必须完全一致）：
 * mode, flags, ownedCount, total, cells, selected, maxOwned；
 * GRID 之后每格追加：name(utf) color(utf) price(long) bits(int)；
 * DETAIL 之后追加：id(utf) name(utf) color(utf) price(long) bits(int)。
 */
public class TitleMenu extends PageMenu {

    // ---------------------------------------------------------------- mode / flags

    public static final int MODE_GRID = 0;
    public static final int MODE_DETAIL = 1;

    /** 本类追加的 sub int 个数。 */
    public static final int SUB_INTS = 7;

    /** 看的是别人的称号页。 */
    public static final int FLAG_OTHER = 1;
    /** 达到 managePermission 且允许管理他人：可查看任意玩家。 */
    public static final int FLAG_MANAGE = 2;
    /** 达到 adminPermission 且允许管理他人：可授予/撤销/强制佩戴。 */
    public static final int FLAG_ADMIN = 4;

    /** bits 位：已拥有。 */
    public static final int BIT_OWNED = 1;
    /** bits 位：当前佩戴。 */
    public static final int BIT_EQUIPPED = 2;

    // ---------------------------------------------------------------- 按钮编号

    public static final int BTN_BUY = BTN_CUSTOM;              // 10
    public static final int BTN_EQUIP = BTN_CUSTOM + 1;        // 11
    public static final int BTN_UNEQUIP = BTN_CUSTOM + 2;      // 12
    public static final int BTN_BACK_GRID = BTN_CUSTOM + 3;    // 13
    public static final int BTN_GRANT = BTN_CUSTOM + 4;        // 14
    public static final int BTN_REVOKE = BTN_CUSTOM + 5;       // 15
    public static final int BTN_FORCE_EQUIP = BTN_CUSTOM + 6;  // 16
    /** 第 r 格（页内行号 0..17）的「查看」按钮 = BTN_INFO_BASE + r。 */
    public static final int BTN_INFO_BASE = BTN_CUSTOM + 20;   // 30..47

    // ---------------------------------------------------------------- 几何常量（与 TitleScreen 共用）

    public static final int COLS = 9;
    public static final int GRID_ROWS = 2;
    public static final int PER_PAGE = COLS * GRID_ROWS;       // 18

    /** 称号墙内容行数（两行图标 + 两行小按钮 ≈ 4 行）。 */
    public static final int GRID_CONTENT_ROWS = 4;
    /** 详情页内容行数（图标 + 4 行文字 + 空余）。 */
    public static final int DETAIL_CONTENT_ROWS = 5;

    /** 一格图标行块的总高：18 图标 + 1 缝 + 12 按钮 + 1 缝。 */
    public static final int BLOCK_H = 32;
    public static final int CELL_BTN_W = 16;
    public static final int CELL_BTN_H = 12;

    public static final int DETAIL_ICON_X = (Layout.PANEL_WIDTH - Layout.PITCH) / 2; // 79
    public static final int DETAIL_ICON_Y = Layout.contentTop();                     // 21
    public static final int DETAIL_NAME_Y = 46;
    public static final int DETAIL_PRICE_Y = 60;
    public static final int DETAIL_OWNED_Y = 74;
    public static final int DETAIL_EQUIP_Y = 88;

    /** 第 cell 格（页内 0..17）图标左上角 x。 */
    public static int cellX(int cell) {
        return Layout.slotX(cell % COLS);
    }

    /** 第 cell 格图标左上角 y。 */
    public static int cellY(int cell) {
        return Layout.contentTop() + (cell / COLS) * BLOCK_H;
    }

    /** 第 cell 格「查」按钮左上角 x。 */
    public static int cellBtnX(int cell) {
        return cellX(cell) + (Layout.PITCH - CELL_BTN_W) / 2;
    }

    /** 第 cell 格「查」按钮左上角 y。 */
    public static int cellBtnY(int cell) {
        return cellY(cell) + Layout.PITCH + 1;
    }

    // ---------------------------------------------------------------- 通用状态

    private final int mode;
    private final int flags;
    private final int ownedCount;
    private final int total;
    private final int cells;
    private final int selected;
    private final int maxOwned;
    /** 详情页记录回到称号墙时的页码；称号墙里就是当前页。 */
    private final int gridPage;

    /** 服务端：被查看的玩家与其数据；客户端为 null。 */
    private final ServerPlayer target;
    private final UUID targetUuid;
    private final TitleData data;

    // ---------------------------------------------------------------- 客户端渲染数据

    private final List<CellView> clientCells = new ArrayList<>();
    private String detailId = "";
    private String detailName = "";
    private String detailColor = "";
    private long detailPrice;
    private int detailBits;

    /** 称号墙一格的显示快照（图标物品本体走只读展示槽同步）。 */
    public record CellView(String name, String color, long price, int bits) {
    }

    // ---------------------------------------------------------------- 构造

    /** 客户端构造：参数顺序固定 (containerId, inventory, extraData)。 */
    public TitleMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(TitleMenus.TITLE_MENU.get(), containerId, inventory);
        applyHeader(readHeader(buf));
        int[] sub = readSubData(buf, SUB_INTS);
        this.mode = sub[0];
        this.flags = sub[1];
        this.ownedCount = Math.max(0, sub[2]);
        this.total = Math.max(0, sub[3]);
        this.cells = Math.max(0, Math.min(PER_PAGE, sub[4]));
        this.selected = sub[5];
        this.maxOwned = Math.max(1, sub[6]);
        this.gridPage = Math.max(0, page());
        this.target = null;
        this.targetUuid = null;
        this.data = null;
        if (mode == MODE_GRID) {
            for (int i = 0; i < cells; i++) {
                String name = buf.readUtf(256);
                String color = buf.readUtf(32);
                long price = buf.readLong();
                int bits = buf.readInt();
                clientCells.add(new CellView(name, color, price, bits));
                addSlot(DisplaySlots.empty(cellX(i), cellY(i)));
            }
        } else {
            detailId = buf.readUtf(64);
            detailName = buf.readUtf(256);
            detailColor = buf.readUtf(32);
            detailPrice = buf.readLong();
            detailBits = buf.readInt();
            addSlot(DisplaySlots.empty(DETAIL_ICON_X, DETAIL_ICON_Y));
        }
    }

    /** 服务端构造（称号墙）。 */
    TitleMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer target, TitleData data,
              int page, int pages, int flags, int ownedCount, int total, int maxOwned,
              List<TitleCatalog.Entry> pageEntries, List<Integer> pageBits) {
        super(TitleMenus.TITLE_MENU.get(), containerId, inventory, viewer, page, pages,
                (flags & FLAG_MANAGE) != 0);
        this.mode = MODE_GRID;
        this.flags = flags;
        this.ownedCount = Math.max(0, ownedCount);
        this.total = Math.max(0, total);
        this.cells = pageEntries == null ? 0 : Math.max(0, pageEntries.size());
        this.selected = -1;
        this.maxOwned = Math.max(1, maxOwned);
        this.gridPage = Math.max(0, page);
        this.target = target;
        this.targetUuid = target.getUUID();
        this.data = data;
        setStatus(ownedCount);
        for (int i = 0; i < cells; i++) {
            addSlot(DisplaySlots.of(pageEntries.get(i).iconStack(), cellX(i), cellY(i)));
        }
    }

    /** 服务端构造（详情）。 */
    TitleMenu(int containerId, Inventory inventory, ServerPlayer viewer, ServerPlayer target, TitleData data,
              int flags, int ownedCount, int total, int maxOwned, int selected, int gridPage,
              TitleCatalog.Entry entry, int bits) {
        super(TitleMenus.TITLE_MENU.get(), containerId, inventory, viewer, 0, 1,
                (flags & FLAG_MANAGE) != 0);
        this.mode = MODE_DETAIL;
        this.flags = flags;
        this.ownedCount = Math.max(0, ownedCount);
        this.total = Math.max(0, total);
        this.cells = 0;
        this.selected = selected;
        this.maxOwned = Math.max(1, maxOwned);
        this.gridPage = Math.max(0, gridPage);
        this.target = target;
        this.targetUuid = target.getUUID();
        this.data = data;
        setStatus(ownedCount);
        if (entry != null) {
            this.detailId = entry.id();
            this.detailName = entry.name();
            this.detailColor = entry.color() == null ? "" : entry.color().getName();
            this.detailPrice = entry.price();
            this.detailBits = bits;
            addSlot(DisplaySlots.of(entry.iconStack(), DETAIL_ICON_X, DETAIL_ICON_Y));
        }
    }

    /**
     * 供 {@link TitleService} 写 openMenu 附加数据：writeFull 是 {@code PageMenu} 的
     * protected 静态方法，只能在子类内部转发。
     */
    public static void writeTitleBuf(RegistryFriendlyByteBuf buf, long balance, int page, int pages,
                                     boolean manage, int status, int mode, int flags, int ownedCount,
                                     int total, int cells, int selected, int maxOwned) {
        writeFull(buf, balance, page, pages, manage, status,
                mode, flags, ownedCount, total, cells, selected, maxOwned);
    }

    /** 称号墙：写完 sub int 后按格追加 name/color/price/bits（顺序与客户端读取一致）。 */
    public static void writeGridCells(RegistryFriendlyByteBuf buf, List<TitleCatalog.Entry> entries,
                                      List<Integer> bitsList) {
        for (int i = 0; i < entries.size(); i++) {
            TitleCatalog.Entry e = entries.get(i);
            buf.writeUtf(e.name());
            buf.writeUtf(e.color() == null ? "" : e.color().getName());
            buf.writeLong(e.price());
            buf.writeInt(bitsList.get(i));
        }
    }

    /** 详情：写完 sub int 后追加 id/name/color/price/bits。 */
    public static void writeDetailData(RegistryFriendlyByteBuf buf, TitleCatalog.Entry entry, int bits) {
        buf.writeUtf(entry == null ? "" : entry.id());
        buf.writeUtf(entry == null ? "" : entry.name());
        buf.writeUtf(entry == null || entry.color() == null ? "" : entry.color().getName());
        buf.writeLong(entry == null ? 0L : entry.price());
        buf.writeInt(bits);
    }

    // ---------------------------------------------------------------- 按钮分发（全部交给 TitleService 二次校验）

    @Override
    protected boolean onAction(ServerPlayer viewer, int buttonId) {
        if (buttonId >= BTN_INFO_BASE && buttonId < BTN_INFO_BASE + PER_PAGE) {
            return TitleService.openRowAt(viewer, this, buttonId - BTN_INFO_BASE);
        }
        return switch (buttonId) {
            case BTN_BUY -> TitleService.purchase(viewer, this);
            case BTN_EQUIP -> TitleService.equipSelf(viewer, this);
            case BTN_UNEQUIP -> TitleService.unequipSelf(viewer, this);
            case BTN_BACK_GRID -> {
                TitleService.open(viewer, targetOrSelf(viewer), gridPage);
                yield true;
            }
            case BTN_GRANT -> TitleService.adminGrantSelected(viewer, this);
            case BTN_REVOKE -> TitleService.adminRevokeSelected(viewer, this);
            case BTN_FORCE_EQUIP -> TitleService.adminForceEquip(viewer, this);
            default -> false;
        };
    }

    @Override
    public void reopen(ServerPlayer viewer, int page) {
        TitleService.open(viewer, targetOrSelf(viewer), page);
    }

    // ---------------------------------------------------------------- 只读访问

    public int mode() {
        return mode;
    }

    public int flags() {
        return flags;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public int ownedCount() {
        return ownedCount;
    }

    public int total() {
        return total;
    }

    public int cells() {
        return cells;
    }

    public int selected() {
        return selected;
    }

    public int maxOwned() {
        return maxOwned;
    }

    public int gridPage() {
        return gridPage;
    }

    /** 本模式下的内容行数（PageScreen 构造用）。 */
    public int contentRows() {
        return mode == MODE_DETAIL ? DETAIL_CONTENT_ROWS : GRID_CONTENT_ROWS;
    }

    public List<CellView> clientCells() {
        return clientCells;
    }

    public String detailId() {
        return detailId;
    }

    public String detailName() {
        return detailName;
    }

    public String detailColor() {
        return detailColor;
    }

    public long detailPrice() {
        return detailPrice;
    }

    public int detailBits() {
        return detailBits;
    }

    public boolean detailOwned() {
        return (detailBits & BIT_OWNED) != 0;
    }

    public boolean detailEquipped() {
        return (detailBits & BIT_EQUIPPED) != 0;
    }

    /** 服务端：被查看的玩家；客户端为 null。 */
    public ServerPlayer target() {
        return target;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    /** 服务端：被查看玩家的称号数据；客户端为 null。 */
    public TitleData data() {
        return data;
    }

    public boolean viewerIs(ServerPlayer player) {
        return viewer != null && viewer == player;
    }

    /** 服务端：目标玩家已离线/换服时回落到查看者。 */
    public ServerPlayer targetOrSelf(ServerPlayer viewer) {
        if (target != null && !target.isRemoved() && target.server == viewer.server) {
            return target;
        }
        return viewer;
    }
}
