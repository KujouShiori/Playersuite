package dev.mcmods.playersuite.client.mail;

import dev.mcmods.playersuite.client.ui.PageScreen;
import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.mail.MailMenu;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 邮件界面：收件箱列表 / 邮件详情 / 写信（含公告）三个页面共用一个 Screen，
 * 按 {@link MailMenu#mode()} 分支绘制与摆放按钮。
 *
 * <p>全部使用 {@link Theme} 矩形绘制，不引用任何材质；列表行的文字在本类的
 * {@link #renderLabels} 中按行绘制（图标由展示槽负责）。
 */
public class MailScreen extends PageScreen<MailMenu> {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Button[] rowOpenButtons = new Button[MailMenu.MAX_LIST_ROWS];
    private final Button[] rowDeleteButtons = new Button[MailMenu.MAX_LIST_ROWS];
    private Button composeButton;
    private Button broadcastButton;
    private Button viewButton;
    private Button clearButton;
    private Button claimButton;
    private Button backToListButton;
    private Button sendButton;
    private Button inputToButton;
    private Button inputTitleButton;
    private Button inputBodyButton;

    public MailScreen(MailMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.contentRows(), menu.mode() == MailMenu.MODE_COMPOSE);
    }

    @Override
    protected String textInputFeature() {
        return FeatureOpeners.MAIL;
    }

    // ---------------------------------------------------------------- 按钮

    @Override
    protected void addExtraButtons(int left, int top, int row1, int row2) {
        int mode = menu.mode();
        if (mode == MailMenu.MODE_LIST) {
            int rows = Math.min(menu.clientRows().size(), MailMenu.MAX_LIST_ROWS);
            for (int r = 0; r < rows; r++) {
                int y = top + MailMenu.listIconY(r) + (MailMenu.LIST_ROW_PITCH - MailMenu.BTN_ROW_H) / 2;
                final int row = r;
                rowOpenButtons[r] = addRenderableWidget(Button.builder(
                        Component.translatable("playersuite.mail.row.open"),
                        b -> sendButton(MailMenu.BTN_SELECT_BASE + row))
                        .bounds(left + MailMenu.BTN_OPEN_X, y, MailMenu.BTN_ROW_W, MailMenu.BTN_ROW_H).build());
                rowDeleteButtons[r] = addRenderableWidget(Button.builder(
                        Component.translatable("playersuite.mail.row.delete"),
                        b -> sendButton(MailMenu.BTN_DELETE_BASE + row))
                        .bounds(left + MailMenu.BTN_DEL_X, y, MailMenu.BTN_ROW_W, MailMenu.BTN_ROW_H).build());
                boolean hasMail = r < menu.clientRows().size();
                rowOpenButtons[r].visible = hasMail;
                rowDeleteButtons[r].visible = hasMail && menu.hasFlag(MailMenu.FLAG_ADMIN);
            }
            composeButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.compose"),
                    b -> sendButton(MailMenu.BTN_COMPOSE))
                    .bounds(left + 8, row1, 38, 20).build());
            broadcastButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.broadcast"),
                    b -> sendButton(MailMenu.BTN_BROADCAST))
                    .bounds(left + 48, row1, 38, 20).build());
            viewButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.view"),
                    b -> openTextInput("view_target",
                            Component.translatable("playersuite.mail.prompt.viewOther"), "", false))
                    .bounds(left + 88, row1, 38, 20).build());
            clearButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.clear"),
                    b -> sendButton(MailMenu.BTN_CLEAR_INBOX))
                    .bounds(left + 128, row1, 38, 20).build());
            composeButton.visible = !menu.hasFlag(MailMenu.FLAG_OTHER);
            broadcastButton.visible = menu.canManage();
            viewButton.visible = menu.hasFlag(MailMenu.FLAG_VIEW);
            clearButton.visible = menu.hasFlag(MailMenu.FLAG_ADMIN);
        } else if (mode == MailMenu.MODE_DETAIL) {
            claimButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.claim"),
                    b -> sendButton(MailMenu.BTN_CLAIM_ALL))
                    .bounds(left + 8, row1, 80, 20).build());
            backToListButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.back"),
                    b -> sendButton(MailMenu.BTN_BACK_TO_LIST))
                    .bounds(left + 94, row1, 74, 20).build());
            claimButton.visible = menu.hasFlag(MailMenu.FLAG_OWNER)
                    && !menu.hasFlag(MailMenu.FLAG_CLAIMED) && menu.displayAttachmentCount() > 0;
        } else if (mode == MailMenu.MODE_COMPOSE) {
            inputToButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.field.to"),
                    b -> openTextInput("to",
                            Component.translatable("playersuite.mail.prompt.to"), menu.composeTextTo(), false))
                    .bounds(left + 140, top + MailMenu.COMPOSE_TO_Y - 1, 28, 10).build());
            inputTitleButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.field.title"),
                    b -> openTextInput("title",
                            Component.translatable("playersuite.mail.prompt.title"),
                            menu.composeTextTitle(), false))
                    .bounds(left + 140, top + MailMenu.COMPOSE_TITLE_Y - 1, 28, 10).build());
            inputBodyButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.field.body"),
                    b -> openTextInput("body",
                            Component.translatable("playersuite.mail.prompt.body"),
                            menu.composeTextBody(), false))
                    .bounds(left + 140, top + MailMenu.COMPOSE_BODY_Y - 1, 28, 10).build());
            sendButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.send"),
                    b -> sendButton(MailMenu.BTN_SEND))
                    .bounds(left + 8, row1, 76, 20).build());
            backToListButton = addRenderableWidget(Button.builder(
                    Component.translatable("playersuite.mail.button.discard"),
                    b -> sendButton(MailMenu.BTN_BACK_TO_LIST))
                    .bounds(left + 92, row1, 76, 20).build());
            inputToButton.visible = !menu.isBroadcast();
        }
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(getGuiLeft(), getGuiTop(), 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        int mode = menu.mode();
        if (mode == MailMenu.MODE_LIST) {
            int rows = Math.min(menu.clientRows().size(), MailMenu.MAX_LIST_ROWS);
            for (int r = 0; r < rows; r++) {
                int y = MailMenu.listIconY(r);
                graphics.fill(4, y, 172, y + MailMenu.LIST_ROW_PITCH - 1, Theme.COLOR_SLOT);
                Theme.slotRow(graphics, y, 1, 1);
                if (r + 1 < rows) {
                    Theme.line(graphics, 4, y + MailMenu.LIST_ROW_PITCH - 1, 168);
                }
            }
        } else if (mode == MailMenu.MODE_DETAIL) {
            Theme.line(graphics, Layout.GRID_LEFT, MailMenu.DETAIL_FROM_Y + 11, 160);
            int att = menu.displayAttachmentCount();
            if (att > 0) {
                Theme.slotRow(graphics, MailMenu.DETAIL_ATT_Y, 1, Math.min(9, att));
            }
        } else if (mode == MailMenu.MODE_COMPOSE) {
            int att = menu.displayAttachmentCount();
            if (att > 0) {
                Theme.slotRow(graphics, MailMenu.COMPOSE_DRAFT_Y, 1, Math.min(9, att));
            }
            int invTop = Layout.inventoryTop(MailMenu.COMPOSE_GRID_ROWS);
            Theme.slotRow(graphics, invTop, 4, 9);
            Theme.line(graphics, Layout.GRID_LEFT, invTop - 15, 9 * Layout.PITCH);
        }
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        switch (menu.mode()) {
            case MailMenu.MODE_LIST -> {
                int r = 0;
                for (MailMenu.RowView row : menu.clientRows()) {
                    if (r >= MailMenu.MAX_LIST_ROWS) {
                        break;
                    }
                    drawMailRow(graphics, r++, row);
                }
            }
            case MailMenu.MODE_DETAIL -> drawDetail(graphics);
            case MailMenu.MODE_COMPOSE -> drawCompose(graphics);
            default -> {
                // 未知 mode 不绘制
            }
        }
    }

    private void drawMailRow(GuiGraphics graphics, int r, MailMenu.RowView row) {
        int y = MailMenu.listIconY(r);
        if (row.unread()) {
            graphics.fill(27, y + 2, 29, y + MailMenu.LIST_ROW_PITCH - 4, Theme.COLOR_WARN);
        }
        Component sender = row.sender().isEmpty()
                ? Component.translatable("playersuite.mail.sender.system")
                : Component.literal(row.sender());
        Theme.text(graphics, ellipsis(this.font, Component.literal(row.title()), 80),
                MailMenu.TEXT_X, y + 2, Theme.COLOR_TEXT);
        Theme.text(graphics, ellipsis(this.font, sender, 80), MailMenu.TEXT_X, y + 12, Theme.COLOR_TEXT_DIM);
        String date;
        synchronized (DATE_FORMAT) {
            date = DATE_FORMAT.format(new Date(row.time()));
        }
        Theme.text(graphics, date, MailMenu.TEXT_X, y + 22, Theme.COLOR_TEXT_DIM);
    }

    private void drawDetail(GuiGraphics graphics) {
        graphics.drawString(this.font,
                ellipsis(this.font, Component.literal(menu.detailTitle()).copy().withStyle(
                        net.minecraft.ChatFormatting.BOLD), 160),
                Layout.GRID_LEFT, MailMenu.DETAIL_TITLE_Y, Theme.COLOR_TEXT);
        Component sender = menu.detailSender().isEmpty()
                ? Component.translatable("playersuite.mail.sender.system")
                : Component.literal(menu.detailSender());
        Theme.text(graphics, ellipsis(this.font,
                Component.translatable("playersuite.mail.detail.from", sender), 100),
                Layout.GRID_LEFT, MailMenu.DETAIL_FROM_Y, Theme.COLOR_TEXT_DIM);
        String date;
        synchronized (DATE_FORMAT) {
            date = DATE_FORMAT.format(new Date(menu.detailTime()));
        }
        Theme.rightText(graphics, date, this.imageWidth, MailMenu.DETAIL_FROM_Y, Theme.COLOR_TEXT_DIM);

        List<FormattedCharSequence> lines = this.font.split(Component.literal(menu.detailBody()),
                MailMenu.DETAIL_BODY_W);
        int pages = Math.max(1, (lines.size() + MailMenu.DETAIL_LINES_PER_PAGE - 1) / MailMenu.DETAIL_LINES_PER_PAGE);
        int page = Math.min(menu.page(), pages - 1);
        int start = page * MailMenu.DETAIL_LINES_PER_PAGE;
        for (int k = 0; k < MailMenu.DETAIL_LINES_PER_PAGE; k++) {
            int idx = start + k;
            if (idx >= lines.size()) {
                break;
            }
            graphics.drawString(this.font, lines.get(idx), Layout.GRID_LEFT,
                    MailMenu.DETAIL_BODY_Y + k * MailMenu.DETAIL_BODY_LINE_H, Theme.COLOR_TEXT);
        }

        int att = menu.displayAttachmentCount();
        if (att > 0) {
            Theme.text(graphics, Component.translatable("playersuite.mail.detail.attachments", att),
                    Layout.GRID_LEFT, MailMenu.DETAIL_ATT_LABEL_Y, Theme.COLOR_TEXT_DIM);
        }
    }

    private void drawCompose(GuiGraphics graphics) {
        Component toValue = menu.isBroadcast()
                ? Component.translatable("playersuite.mail.compose.toAll")
                : valueOrUnset(menu.composeTextTo());
        Component titleValue = valueOrUnset(menu.composeTextTitle());
        Component bodyValue = valueOrUnset(menu.composeTextBody());
        Theme.text(graphics, ellipsis(this.font,
                Component.translatable("playersuite.mail.compose.to", toValue), 132),
                Layout.GRID_LEFT, MailMenu.COMPOSE_TO_Y, Theme.COLOR_TEXT);
        Theme.text(graphics, ellipsis(this.font,
                Component.translatable("playersuite.mail.compose.title", titleValue), 132),
                Layout.GRID_LEFT, MailMenu.COMPOSE_TITLE_Y, Theme.COLOR_TEXT);
        Theme.text(graphics, ellipsis(this.font,
                Component.translatable("playersuite.mail.compose.body", bodyValue), 132),
                Layout.GRID_LEFT, MailMenu.COMPOSE_BODY_Y, Theme.COLOR_TEXT_DIM);
    }

    private Component valueOrUnset(String value) {
        return value == null || value.isEmpty()
                ? Component.translatable("playersuite.mail.compose.unset")
                : Component.literal(value);
    }

    @Override
    protected Component headerRight() {
        if (menu.mode() != MailMenu.MODE_LIST) {
            return null;
        }
        int unread = menu.status();
        if (unread > 0) {
            return Component.translatable("playersuite.mail.header.unread", unread, menu.total());
        }
        return Component.translatable("playersuite.mail.header.readAll", menu.total());
    }

    @Override
    protected Component statusLine() {
        return switch (menu.mode()) {
            case MailMenu.MODE_LIST -> {
                if (menu.total() <= 0) {
                    yield Component.translatable("playersuite.mail.list.empty");
                }
                yield null;
            }
            case MailMenu.MODE_DETAIL -> {
                if (!menu.hasFlag(MailMenu.FLAG_OWNER)) {
                    yield Component.translatable("playersuite.mail.detail.viewingOther");
                }
                if (menu.hasFlag(MailMenu.FLAG_CLAIMED)) {
                    yield Component.translatable("playersuite.mail.detail.claimed");
                }
                if (menu.displayAttachmentCount() <= 0) {
                    yield Component.translatable("playersuite.mail.detail.noAttachments");
                }
                yield null;
            }
            case MailMenu.MODE_COMPOSE -> {
                if (menu.isBroadcast()) {
                    yield Component.translatable("playersuite.mail.compose.free", menu.status());
                }
                yield Component.translatable("playersuite.mail.compose.cost",
                        Economy.label(menu.extra()), menu.status());
            }
            default -> null;
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (sendButton != null && menu.mode() == MailMenu.MODE_COMPOSE) {
            sendButton.active = menu.balance() >= menu.extra();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (viewButton != null && viewButton.visible && viewButton.isHovered()) {
            graphics.renderComponentTooltip(this.font,
                    List.<Component>of(Component.translatable("playersuite.mail.button.view.hint")),
                    mouseX, mouseY);
            return;
        }
        if (clearButton != null && clearButton.visible && clearButton.isHovered()) {
            graphics.renderComponentTooltip(this.font,
                    List.<Component>of(Component.translatable("playersuite.mail.button.clear.hint")),
                    mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- 文本截断

    /** 按像素宽度截断文本，超出补省略号（避免长标题撑破行）。 */
    static Component ellipsis(net.minecraft.client.gui.Font font, Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String s = text.getString();
        int dots = font.width("...");
        int cut = s.length();
        while (cut > 0 && font.width(s.substring(0, cut)) + dots > maxWidth) {
            cut--;
        }
        return Component.literal(s.substring(0, Math.max(0, cut)) + "...");
    }
}
