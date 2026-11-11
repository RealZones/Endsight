package com.endsight.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

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

    /**
     * The key that opens this menu. It stays a vanilla KeyMapping, so Controls and anyone's
     * existing choice keep working, and this id is how the Keybinds page reaches it.
     */
    public static final String OPEN_MENU = "menu.open";
    private static IntSupplier openKey = () -> NONE;
    private static IntConsumer setOpenKey = key -> { };

    private static final Map<String, Integer> bound = new LinkedHashMap<>();
    /** What all() hands out, until a bind changes: it is asked for every tick. */
    private static Map<String, Integer> snapshot;

    private static final Map<String, String> moduleIds = new HashMap<>();

    /**
     * The module switches worth a key: things you flip in the middle of playing. Every module
     * used to get one, so the Keybinds page was mostly Ice Path and Math Solver, features
     * nobody turns on and off by key.
     */
    private static final Set<String> SWITCHES = Set.of("module.qol.autoclicker", "module.visual.fullbright",
            "module.visual.explosionParticles", "module.dragon.hitboxes", "module.visual.perspective", "module.visual.camera");

    /**
     * Whether a key belongs on the pages. A real action always does; a module's switch only if
     * it is listed, or if someone already set it, because a key that still works and cannot be
     * seen cannot be cleared either.
     */
    public static boolean worthAKey(String id) {
        return !id.startsWith("module.") || SWITCHES.contains(id) || get(id) != NONE;
    }

    /** Kept: the browser asks for every row, every frame. */
    public static String moduleId(Module m) {
        String id = moduleIds.get(m.id());
        if (id == null) moduleIds.put(m.id(), id = "module." + m.id());
        return id;
    }

    public static void openMenuKey(IntSupplier read, IntConsumer write) {
        openKey = read;
        setOpenKey = write;
    }

    public static int get(String id) {
        return OPEN_MENU.equals(id) ? openKey.getAsInt() : bound.getOrDefault(id, NONE);
    }

    /**
     * Bind a key, taking it off whatever held it.
     *
     * One key, one action: two actions on one key means a press does something you did
     * not ask for, and the binding screen has no room to warn about it.
     */
    public static void set(String id, int key) {
        snapshot = null;
        if (key == NONE) {
            clear(id);
            return;
        }
        // Never taken off the menu key: nothing else here could open the menu to put it back.
        if (!OPEN_MENU.equals(id) && key == openKey.getAsInt()) return;
        bound.values().removeIf(k -> k == key);
        if (OPEN_MENU.equals(id)) setOpenKey.accept(key);
        else bound.put(id, key);
    }

    public static void clear(String id) {
        snapshot = null;
        // Back to its default rather than to nothing, for the same reason.
        if (OPEN_MENU.equals(id)) setOpenKey.accept(NONE);
        else bound.remove(id);
    }

    public static Map<String, Integer> all() {
        if (snapshot == null) snapshot = Map.copyOf(bound);
        return snapshot;
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

    public static int mouseButton(int key) {
        return MOUSE_BASE - key;
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
        // Shortened first, or Right Shift, the menu's own default, reads "Right S" on the chip.
        String name = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString()
                .replace("Right ", "R ").replace("Left ", "L ");
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
        chip(g, font, id, x, y, hover, listening, false);
    }

    /**
     * {@code onTile} is for a chip on a raised card. The usual bound chip is raised itself,
     * so it vanished into the tile and left a key name floating there; a bound key is also
     * what you look for across a grid of tiles, so it takes the accent.
     */
    public static void chip(GuiGraphicsExtractor g, Font font, String id, int x, int y,
                            float hover, boolean listening, boolean onTile) {
        int key = get(id);
        boolean accented = onTile && key != NONE && !listening;
        int fill = listening ? Draw.alpha(Theme.accent(), 0.28f)
                : accented ? Draw.alpha(Theme.accent(), 0.2f + 0.14f * hover)
                : Draw.lerp(key == NONE ? Theme.bg() : Theme.raised(), Theme.hover(), hover);
        Draw.roundedRect(g, x, y, CHIP_W, CHIP_H, Theme.RADIUS - 2, fill);
        if (key == NONE && !listening) {
            Draw.roundedOutline(g, x, y, CHIP_W, CHIP_H, Theme.RADIUS - 2, Theme.line(), Theme.bg());
        }
        // A bare dash looked like a disabled control in the module list. "Bind"
        // makes an empty, clickable key slot understandable without a tooltip.
        String text = listening ? "..." : key == NONE ? "Bind" : label(key);
        Draw.textCentered(g, font, text, x + CHIP_W / 2, y + 4,
                listening ? Theme.accent()
                        : accented ? Draw.lerp(Theme.accent(), Theme.text(), hover)
                        : key == NONE ? Draw.lerp(Theme.dim(), Theme.muted(), hover)
                        : Draw.lerp(Theme.muted(), Theme.text(), hover));
    }

}
