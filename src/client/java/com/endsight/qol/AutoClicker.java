package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/** Repeated attacks while the physical left mouse button is held in focused gameplay. */
public final class AutoClicker {
    private static boolean enabled;
    private static double minCps = 6, maxCps = 9;
    private static final ClickCadence cadence = newCadence();

    private AutoClicker() { }

    public static Module module() {
        return new Module("qol.autoclicker", "Auto Clicker", "Hold left click to repeat attacks at a varying rate.", "General",
                () -> enabled, value -> { enabled = value; cadence.reset(); }, List.of(
                new Setting.Slider("Min CPS", "Lower end of the click-rate range.", 1, 20, 0.5,
                        () -> minCps, value -> minCps = Math.min(value, maxCps), ""),
                new Setting.Slider("Max CPS", "Upper end of the click-rate range.", 1, 20, 0.5,
                        () -> maxCps, value -> maxCps = Math.max(value, minCps), "")));
    }

    public static boolean enabled() { return enabled; }

    /** Separate schedules may share the chosen rate without consuming one another's clicks. */
    public static ClickCadence newCadence() {
        return new ClickCadence(() -> minCps, () -> maxCps);
    }

    public static Map<String, Object> settingsSnapshot() {
        return Map.of("enabled", enabled, "minCps", minCps, "maxCps", maxCps);
    }

    /** Poll per frame; tick-only timing would round every interval to 50 ms. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.screen != null
                || mc.getWindow() == null || !mc.isWindowActive() || !mc.player.isAlive()) {
            cadence.reset();
            return;
        }
        boolean held = GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!held) { cadence.reset(); return; }
        // Treat repeats as presses: vanilla's held-attack path mines blocks and retains
        // a miss cooldown, which would swallow subsequent clicks after a swing at air.
        if (mc.options.keyAttack.isDown()) mc.options.keyAttack.setDown(false);
        if (cadence.poll(System.nanoTime()))
            KeyMapping.click(InputConstants.getKey(mc.options.keyAttack.saveString()));
    }
}
