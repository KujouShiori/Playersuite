package dev.mcmods.playersuite.client.ui;

import dev.mcmods.playersuite.client.ClientNet;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.PageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 各功能界面的公共父类：统一面板绘制、余额行、翻页按钮、返回总入口按钮。
 *
 * <p>子类通常只需要做三件事：
 * <ol>
 *     <li>构造函数里 {@code super(menu, inventory, title, 内容行数, 是否显示玩家物品栏)}；</li>
 *     <li>重写 {@link #addExtraButtons} 放置功能自己的按钮（点击用 {@link #sendButton}）；</li>
 *     <li>重写 {@link #headerRight} / {@link #statusLine} 提供本功能自己的文字。</li>
 * </ol>
 *
 * <p>界面完全用矩形绘制，不依赖任何材质资源，因此不会出现缺材质黑框。
 */
public abstract class PageScreen<M extends PageMenu> extends AbstractContainerScreen<M> {
    protected final int contentRows;
    protected final boolean withInventory;

    protected Button prevButton;
    protected Button nextButton;
    protected Button backButton;

    protected PageScreen(M menu, Inventory inventory, Component title, int contentRows, boolean withInventory) {
        super(menu, inventory, title);
        this.contentRows = Math.max(0, contentRows);
        this.withInventory = withInventory;
        this.imageWidth = Layout.PANEL_WIDTH;
        this.imageHeight = Layout.panelHeight(this.contentRows, withInventory);
        this.titleLabelX = Layout.GRID_LEFT;
        this.titleLabelY = 5;
        this.inventoryLabelX = Layout.GRID_LEFT;
        this.inventoryLabelY = Layout.inventoryTop(this.contentRows) - 12;
    }

    // ------------------------------------------------------------ 按钮

    @Override
    protected void init() {
        // 1.21.1 中 Screen#init(Minecraft,int,int) 是 final，只能重写无参 init()
        super.init();
        int left = getGuiLeft();
        int top = getGuiTop();
        int row1 = top + Layout.footerRow1(contentRows, withInventory);
        int row2 = top + Layout.footerRow2(contentRows, withInventory);

        backButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.hub"),
                button -> sendButton(PageMenu.BTN_BACK_TO_HUB)).bounds(left + 8, row2, 60, 20).build());
        prevButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.prev"),
                button -> sendButton(PageMenu.BTN_PREV_PAGE)).bounds(left + 72, row2, 40, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.translatable("playersuite.button.next"),
                button -> sendButton(PageMenu.BTN_NEXT_PAGE)).bounds(left + 116, row2, 52, 20).build());

        boolean paging = menu.pages() > 1;
        backButton.visible = true;
        prevButton.visible = paging;
        nextButton.visible = paging;
        prevButton.active = menu.page() > 0;
        nextButton.active = menu.page() < menu.pages() - 1;

        addExtraButtons(left, top, row1, row2);
    }

    /**
     * 子类放自己的按钮。
     *
     * @param row1 底部第一行按钮的绝对 y
     * @param row2 底部第二行按钮的绝对 y（已被公共按钮占用，功能按钮建议放 row1 或内容区右侧）
     */
    protected void addExtraButtons(int left, int top, int row1, int row2) {
    }

    /** 发送容器按钮点击（服务端回调 {@code PageMenu#onAction}）。 */
    protected void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null && this.menu.containerId >= 0) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    /**
     * 打开一个文本输入框，确认后用 {@link ClientNet#sendText} 把内容交给服务端。
     *
     * @param action  功能内部定义的动作名
     * @param prompt  输入框上方的提示文字
     * @param numeric 是否只允许输入数字（提交前在服务端还会再校验一次）
     */
    protected void openTextInput(String action, Component prompt, String defaultValue, boolean numeric) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new TextInputScreen(this, textInputFeature(), action, prompt, defaultValue, numeric));
    }

    /** 子类返回自己的功能键（见 {@code FeatureOpeners}）。 */
    protected abstract String textInputFeature();

    // ------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (prevButton != null && menu.pages() > 1) {
            prevButton.active = menu.page() > 0;
            nextButton.active = menu.page() < menu.pages() - 1;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = getGuiLeft();
        int top = getGuiTop();
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        int rows = Math.max(1, contentRows);
        Theme.slotRow(graphics, Layout.contentTop(), rows, 9);
        if (withInventory) {
            int invTop = Layout.inventoryTop(contentRows);
            Theme.slotRow(graphics, invTop, 4, 9);
            Theme.line(graphics, Layout.GRID_LEFT, invTop - 15, 9 * Layout.PITCH);
        }
        renderPageBackground(graphics);
        graphics.pose().popPose();
    }

    /** 子类补充绘制（选中行高亮、进度条等），坐标系已平移到面板左上角。 */
    protected void renderPageBackground(GuiGraphics graphics) {
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, Theme.COLOR_TEXT);
        Component right = headerRight();
        if (right != null) {
            Theme.rightText(graphics, right, this.imageWidth, this.titleLabelY, Theme.COLOR_TEXT);
        }
        int footerY = Layout.footerRow1(contentRows, withInventory) - 12;
        if (menu.pages() > 1) {
            Theme.text(graphics, Component.translatable("playersuite.hud.page", menu.page() + 1, menu.pages()),
                    Layout.GRID_LEFT, footerY, Theme.COLOR_TEXT_DIM);
        }
        Theme.rightText(graphics, Component.translatable("playersuite.hud.balance",
                Economy.label(menu.balance())), this.imageWidth, footerY, Theme.COLOR_TEXT);
        Component status = statusLine();
        if (status != null) {
            int statusY = withInventory
                    ? Layout.inventoryTop(contentRows) - 12
                    : Layout.contentTop() + Math.max(1, contentRows) * Layout.PITCH + 4;
            Theme.text(graphics, status, Layout.GRID_LEFT, statusY, Theme.COLOR_TEXT_DIM);
        }
    }

    /** 标题右侧文字（例如「未读 3 / 共 12 封」）。 */
    protected Component headerRight() {
        return null;
    }

    /** 内容区与底部按钮之间的说明行。 */
    protected Component statusLine() {
        return null;
    }
}
