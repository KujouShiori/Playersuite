package dev.mcmods.playersuite.client.hub;

import dev.mcmods.playersuite.client.ui.Theme;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.hub.HubMenu;
import dev.mcmods.playersuite.menu.Layout;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 总入口界面：一条指令 {@code /ps} 打开这里，七个功能以按钮形式进入各自子页面。
 *
 * <p>按钮的可用状态来自服务端的启用位掩码（配置在服务器端，客户端本地配置不作数）。
 */
public class HubScreen extends AbstractContainerScreen<HubMenu> {
    private final Button[] featureButtons = new Button[FeatureOpeners.FEATURES.size()];

    public HubScreen(HubMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = Layout.PANEL_WIDTH;
        this.imageHeight = HubMenu.PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int left = getGuiLeft();
        int top = getGuiTop();
        for (int i = 0; i < featureButtons.length; i++) {
            String key = FeatureOpeners.keyAt(i);
            final int index = i;
            int colX = (i % 2 == 0) ? HubMenu.COL_LEFT : HubMenu.COL_RIGHT;
            int rowY = HubMenu.BUTTON_TOP + (i / 2) * HubMenu.BUTTON_PITCH;
            Button button = Button.builder(Component.translatable("playersuite.hub.feature." + key),
                            b -> sendButton(HubMenu.BTN_FEATURE_BASE + index))
                    .bounds(left + colX, top + rowY, HubMenu.BUTTON_W, HubMenu.BUTTON_H)
                    .build();
            button.active = menu.featureEnabled(i);
            featureButtons[i] = addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.translatable("playersuite.hub.claim"),
                        b -> sendButton(HubMenu.BTN_CLAIM))
                .bounds(left + 8, top + HubMenu.CLAIM_TOP, 160, 20).build());
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null && this.menu.containerId >= 0) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(getGuiLeft(), getGuiTop(), 0);
        Theme.panel(graphics, this.imageWidth, this.imageHeight);
        Theme.header(graphics, this.imageWidth, Layout.HEADER);
        Theme.slotRow(graphics, HubMenu.INVENTORY_TOP, 4, 9);
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, Layout.GRID_LEFT, 5, Theme.COLOR_TEXT);
        Theme.rightText(graphics, Component.translatable("playersuite.hud.balance",
                Economy.label(this.menu.balance())), this.imageWidth, 5, Theme.COLOR_TEXT);
        int pending = this.menu.pendingCount();
        int unread = this.menu.mailUnread();
        Component hint;
        int hintColor;
        if (pending > 0) {
            hint = Component.translatable("playersuite.hub.pendingHint", pending);
            hintColor = Theme.COLOR_OK;
        } else if (unread > 0) {
            hint = Component.translatable("playersuite.hub.unreadHint", unread);
            hintColor = Theme.COLOR_HIGHLIGHT;
        } else {
            hint = Component.translatable("playersuite.hub.guideHint");
            hintColor = Theme.COLOR_TEXT_DIM;
        }
        Theme.text(graphics, hint,
                Layout.GRID_LEFT, HubMenu.CLAIM_TOP - 10, hintColor);
        Theme.text(graphics, Component.translatable("playersuite.inventory"),
                Layout.GRID_LEFT, HubMenu.INVENTORY_TOP - 12, Theme.COLOR_TEXT_DIM);
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        for (int i = 0; i < featureButtons.length; i++) {
            Button button = featureButtons[i];
            if (button != null && !button.active && button.isHovered()) {
                graphics.renderComponentTooltip(this.font,
                        List.<Component>of(Component.translatable("playersuite.ui.disabled",
                                Component.translatable("playersuite.hub.feature." + FeatureOpeners.keyAt(i)).getString())),
                        mouseX, mouseY);
                break;
            }
        }
    }
}
