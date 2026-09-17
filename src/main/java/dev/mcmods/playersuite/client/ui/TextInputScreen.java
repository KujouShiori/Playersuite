package dev.mcmods.playersuite.client.ui;

import dev.mcmods.playersuite.client.ClientNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通用文本输入框：所有需要打字的动作（写邮件正文、设定售价、输入金额、命名店铺、
 * 新建称号……）都复用这一个界面，确认后通过 {@link ClientNet#sendText} 发回服务端。
 *
 * <p>打开本界面时底下的容器菜单在服务端依然保持打开，按 Esc 或「取消」会回到原界面。
 */
public class TextInputScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 96;

    private final Screen returnTo;
    private final String feature;
    private final String action;
    private final Component prompt;
    private final boolean numeric;
    private final String defaultValue;
    private EditBox input;

    public TextInputScreen(Screen returnTo, String feature, String action, Component prompt,
                           String defaultValue, boolean numeric) {
        super(Component.translatable("playersuite.input.title"));
        this.returnTo = returnTo;
        this.feature = feature;
        this.action = action;
        this.prompt = prompt;
        this.numeric = numeric;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - WIDTH) / 2;
        int top = (this.height - HEIGHT) / 2;
        this.input = new EditBox(this.font, left + 10, top + 40, WIDTH - 20, 20,
                Component.translatable("playersuite.input.field"));
        this.input.setValue(trim(this.defaultValue));
        this.input.setBordered(true);
        this.input.setHint(Component.translatable(numeric ? "playersuite.input.hint.number" : "playersuite.input.hint.text"));
        addRenderableWidget(this.input);
        addRenderableWidget(Button.builder(Component.translatable("playersuite.input.confirm"),
                button -> submit()).bounds(left + 10, top + HEIGHT - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("playersuite.input.cancel"),
                button -> close()).bounds(left + WIDTH - 110, top + HEIGHT - 28, 100, 20).build());
        setInitialFocus(this.input);
    }

    private String trim(String raw) {
        String value = raw == null ? "" : raw;
        if (value.length() > 256) {
            value = value.substring(0, 256);
        }
        return numeric ? value.replaceAll("[^0-9]", "") : value;
    }

    private void submit() {
        if (this.input == null) {
            return;
        }
        String text = trim(this.input.getValue());
        if (text.isBlank()) {
            return;
        }
        ClientNet.sendText(feature, action, text);
        close();
    }

    private void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(returnTo);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.input != null && this.input.isFocused() && (keyCode == 257 || keyCode == 335)) {
            submit();
            return true;
        }
        if (this.input != null && this.input.isFocused() && keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - WIDTH) / 2;
        int top = (this.height - HEIGHT) / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 40);
        graphics.drawString(this.font, this.title, left + 10, top + 12, Theme.COLOR_TEXT);
        graphics.drawWordWrap(this.font, this.prompt, left + 10, top + 24, WIDTH - 20, Theme.COLOR_TEXT_DIM);
        graphics.pose().popPose();
    }
}
