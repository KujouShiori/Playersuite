package dev.mcmods.playersuite.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 鼠标位置保持（解决"点翻页/切页时鼠标跳回屏幕正中"）。
 *
 * <p>原因：本模组的翻页与切页会重开容器。服务端在"已有容器时再开新容器"时会先发送
 * {@code ClientboundCloseScreen} 再发送 {@code ClientboundOpenScreen}，客户端于是走
 * {@code setScreen(null) -> setScreen(new)}；而原版 {@code MouseHandler#grabMouse()} 在重新
 * 捕获鼠标时会把内部坐标写成 {@code xpos = 窗口宽/2, ypos = 窗口高/2} 并调用 GLFW 移动光标，
 * 于是玩家看到鼠标"啪"地回到屏幕中心（原版箱子/熔炉来回切时也有同样问题）。
 *
 * <p>做法：界面每帧记住鼠标的**真实像素**坐标；切换到我们自己的新界面后，在**第一帧渲染前**
 * （此时原版的 grabMouse 已经执行完）用 {@code GLFW.glfwSetCursorPos} 放回原位。GLFW 的光标回调
 * 会把 {@code MouseHandler} 内部坐标同步回来，因此界面高亮、拖拽物品都不会错位。
 *
 * <p>只在"上一次记录 400ms 内"才恢复，避免玩家隔了几秒才打开界面时把光标乱塞到旧位置；
 * 也只作用于本模组自己的界面（只有我们的 Screen 会调用这里），不影响其它模组的界面。
 */
public final class CursorKeeper {
    /** 恢复时间窗：超过这个间隔就不认为是"同一次连续操作"。 */
    private static final long RESTORE_WINDOW_MS = 400L;

    private static double lastX = -1.0;
    private static double lastY = -1.0;
    private static long lastSeenMs;
    private static boolean pending;
    /** 刚恢复过：这一帧的 mouseX 还是旧的中心值，不能拿去覆盖记录。 */
    private static boolean suppressRemember;

    private CursorKeeper() {
    }

    /** 界面每帧调用，记录鼠标位置（GUI 缩放坐标 -> 真实像素）。 */
    public static void remember(Minecraft mc, double guiScaledMouseX, double guiScaledMouseY) {
        if (suppressRemember) {
            suppressRemember = false;
            return;
        }
        if (mc == null) {
            return;
        }
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0.0) {
            return;
        }
        lastX = guiScaledMouseX * scale;
        lastY = guiScaledMouseY * scale;
        lastSeenMs = System.currentTimeMillis();
    }

    /** 新界面 {@code init()} 里调用：判断是否需要把光标放回上一次的位置。 */
    public static void armRestore() {
        pending = lastSeenMs > 0L && System.currentTimeMillis() - lastSeenMs <= RESTORE_WINDOW_MS;
    }

    /** 新界面第一帧 {@code render()} 开头调用（必须在 super.render 之前）。 */
    public static void restoreIfPending(Minecraft mc) {
        if (!pending) {
            return;
        }
        pending = false;
        if (mc == null || lastX < 0.0 || lastY < 0.0) {
            return;
        }
        Window window = mc.getWindow();
        long handle = window.getWindow();
        if (handle == 0L) {
            return;
        }
        double x = clamp(lastX, window.getWidth());
        double y = clamp(lastY, window.getHeight());
        GLFW.glfwSetCursorPos(handle, x, y);
        suppressRemember = true;   // 本帧 mouseX 仍是旧的中心值，别覆盖掉记录
    }

    private static double clamp(double value, double max) {
        if (max <= 0.0) {
            return value;
        }
        return Math.max(0.0, Math.min(value, max - 1.0));
    }
}
