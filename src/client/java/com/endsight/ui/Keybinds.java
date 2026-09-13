package com.endsight.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keys bound to things that can be switched, by id.
 *
 * A map of id to key rather than a field on each module or setting, because the things
 * that can be bound are described as data and half of them are records - there is
 * nowhere on a {@code Setting.Toggle} to keep a key without making every setting carry
 * a field only two kinds ever use. The id is built from the module and, for a setting,
 * its label, which is the same key the config file already saves values under.
 *
 * Held here rather than as vanilla {@code KeyMapping}s for the reason the open key is
 * polled: a KeyMapping is dead while a screen is open, and a key that only works when
 * you are not looking at anything is the wrong shape for a toggle you want mid-fight.
 */
public final class Keybinds {

    private Keybinds() {
    }

    /** GLFW's "no key". */
    public static final int NONE = -1;

    private static final Map<String, Integer> bound = new LinkedHashMap<>();

    public static String moduleId(Module m) {
        return "module." + m.id();
    }

    public static int get(String id) {
        return bound.getOrDefault(id, NONE);
    }

    /**
     * Bind a key, taking it off whatever held it.
     *
     * One key, one action: two actions on one key means a press does something you did
     * not ask for, and the binding screen has no room to warn about it.
     */
    public static void set(String id, int key) {
        if (key == NONE) {
            bound.remove(id);
            return;
        }
        bound.values().removeIf(k -> k == key);
        bound.put(id, key);
    }

    public static void clear(String id) {
        bound.remove(id);
    }

    public static Map<String, Integer> all() {
        return Map.copyOf(bound);
    }

    /**
     * Mouse buttons share the int with keyboard keys, below the range keys use: a
     * button b is stored as {@code -100 - b}, so side button 1 (GLFW button 3) is -103.
     * NONE is -1, which is outside that range, and every keyboard key is positive.
     */
    private static final int MOUSE_BASE = -100;

    public static int mouse(int button) {
        return MOUSE_BASE - button;
    }

    public static boolean isMouse(int key) {
        return key <= MOUSE_BASE;
    }

    /** Whether the bound key or button is down right now. */
    public static boolean isDown(com.mojang.blaze3d.platform.Window window, int key) {
        if (key == NONE) return false;
        if (isMouse(key)) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(window.handle(), MOUSE_BASE - key)
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key);
    }

    /** What to print on the chip: the key's own name, M3/M4/M5 for a mouse button, a dash for unbound. */
    public static String label(int key) {
        if (key == NONE) return "-";
        if (isMouse(key)) return "M" + (MOUSE_BASE - key + 1);
        String name = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return name.length() > 7 ? name.substring(0, 7) : name;
    }

    // ── the chip ──────────────────────────────────────────────────────────────

    public static final int CHIP_W = 40;
    public static final int CHIP_H = 16;

    /**
     * The key chip, drawn the same wherever it sits: on a settings row, in the settings
     * sidebar, or on a card in the browser. One drawing so they cannot drift apart.
     * {@code hover} is the screen's own animated 0..1; {@code listening} is whether this
     * chip is the one waiting for a key.
     */
    public static void chip(GuiGraphicsExtractor g, Font font, String id, int x, int y,
                            float hover, boolean listening) {
        int key = get(id);
        int fill = listening ? Draw.alpha(Theme.accent(), 0.28f)
                : Draw.lerp(key == NONE ? Theme.bg() : Theme.raised(), Theme.hover(), hover);
        Draw.roundedRect(g, x, y, CHIP_W, CHIP_H, Theme.RADIUS - 2, fill);
        if (key == NONE && !listening) {
            Draw.roundedOutline(g, x, y, CHIP_W, CHIP_H, Theme.RADIUS - 2, Theme.line(), Theme.bg());
        }
        String text = listening ? "..." : label(key);
        Draw.textCentered(g, font, text, x + CHIP_W / 2, y + 4,
                listening ? Theme.accent()
                        : key == NONE ? Draw.lerp(Theme.dim(), Theme.muted(), hover)
                        : Draw.lerp(Theme.muted(), Theme.text(), hover));
    }

}
