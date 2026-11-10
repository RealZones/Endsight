package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Repeated attacks, and optionally item uses, while the physical mouse button is held in focused gameplay. */
public final class AutoClicker {
    private static boolean enabled, right;
    private static double minCps = 6, maxCps = 9;
    // One schedule per button: holding both must not let one eat the other's clicks.
    private static final ClickCadence attacks = newCadence(), uses = newCadence();

    private AutoClicker() { }

    public static Module module() {
        return new Module("qol.autoclicker", "Auto Clicker", "Hold left click to repeat attacks at a varying rate.", "General",
                () -> enabled, value -> { enabled = value; attacks.reset(); uses.reset(); }, List.of(
                new Setting.Slider("Min CPS", "Lower end of the click-rate range; capped at 9.5 CPS.", 1, ClickCadence.MAX_CPS, 0.5,
                        () -> minCps, value -> minCps = Math.min(settingCps(value, minCps), maxCps), ""),
                new Setting.Slider("Max CPS", "Upper end of the click-rate range; capped at 9.5 CPS.", 1, ClickCadence.MAX_CPS, 0.5,
                        () -> maxCps, value -> maxCps = Math.max(settingCps(value, maxCps), minCps), ""),
                new Setting.Toggle("Right click", "Repeat right click as well while it is held.",
                        () -> right, value -> { right = value; uses.reset(); })));
    }

    private static double settingCps(double value, double fallback) {
        return Double.isNaN(value) ? fallback : Math.max(1, Math.min(ClickCadence.MAX_CPS, value));
    }

    public static boolean enabled() { return enabled; }

    /** Separate schedules may share the chosen rate without consuming one another's clicks. */
    public static ClickCadence newCadence() {
        return new ClickCadence(() -> minCps, () -> maxCps);
    }

    public static Map<String, Object> settingsSnapshot() {
        return Map.of("enabled", enabled, "right", right, "minCps", minCps, "maxCps", maxCps);
    }

    /** Poll per frame; tick-only timing would round every interval to 50 ms. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.screen != null
                || mc.getWindow() == null || !mc.isWindowActive() || !mc.player.isAlive()) {
            attacks.reset();
            uses.reset();
            return;
        }
        long now = System.nanoTime();
        // A pickaxe or drill on a block is mining, which needs the button held, not
        // clicked. The clicker used to let go of the attack key every frame, so with it on
        // nothing could be mined (Fear, 2026-11-09). Anything else still clicks, so a
        // left-click weapon keeps firing when it is aimed at a wall.
        boolean mining = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK
                && miningTool(mc.player.getMainHandItem());
        repeat(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, mc.options.keyAttack, attacks, now, !mining);
        // A bow, food or shield has to be held to draw, eat or block; clicking would cut
        // every one short.
        if (right) repeat(mc, GLFW.GLFW_MOUSE_BUTTON_RIGHT, mc.options.keyUse, uses, now,
                mc.player.getMainHandItem().getUseDuration(mc.player) <= 0);
        else uses.reset();
    }

    private static void repeat(Minecraft mc, int button, KeyMapping key, ClickCadence schedule, long now, boolean click) {
        boolean held = GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
        if (!held) { schedule.reset(); return; }
        if (!click) {
            // Hand the button back to the game. Earlier frames let go of the key, and the
            // game only presses it again on a new mouse press, so it is pressed here.
            if (!key.isDown()) key.setDown(true);
            schedule.reset();
            return;
        }
        // Treat repeats as presses: the game's held-button path mines blocks and keeps a
        // miss cooldown, which would swallow later clicks after a swing at air; held use
        // repeats on its own every four ticks, on top of these.
        if (key.isDown()) key.setDown(false);
        if (schedule.poll(now)) KeyMapping.click(InputConstants.getKey(key.saveString()));
    }

    /** The mod's usual test for a mining tool: a pickaxe, or anything named a drill. */
    private static boolean miningTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(ItemTags.PICKAXES)) return true;
        String name = Zealots.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
        return name.contains("pickaxe") || name.contains("drill") && !name.contains("drill motor");
    }
}
