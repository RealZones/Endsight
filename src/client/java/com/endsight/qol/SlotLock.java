package com.endsight.qol;

import com.endsight.hud.Toast;
import com.endsight.ui.Draw;
import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Slots that will not give up what is in them, and pairs that swap on a shift-click.
 *
 * Locks are kept per inventory slot, never per item: the point is that the slot your
 * hyperion lives in cannot be emptied by a misclick, and an item you move in there on
 * purpose is protected by the same rule. Armour and the off-hand come along for free,
 * because they are inventory slots like any other - 36 to 39 and 40.
 *
 * Client-initiated container clicks are stopped before a packet is sent. This cannot
 * prevent the server itself from changing an inventory slot.
 */
public final class SlotLock {
    private static final String ID = "qol.slotLock";
    private static final String KEY_ID = ID + ".lock";
    private static final int HOTBAR_END = 8;
    private static final int MAIN_END = 35;
    private static final int OFFHAND = 40;

    private static boolean enabled;
    /** Player inventory slot indices, not screen slot indices: a chest moves those about. */
    private static final Set<Integer> LOCKED = new LinkedHashSet<>();
    /** Both directions, so either end of a pair can be the one you shift-click. */
    private static final Map<Integer, Integer> BOUND = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean keyHeld;
    private static int heldFrom = -1;
    private static AbstractContainerScreen<?> heldScreen;

    private SlotLock() {
    }

    public static Module module() {
        return new Module(ID, "Slot Lock", "Lock slots; link a main slot to your hotbar for Shift-click swaps.", "Quality of Life",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) clearGesture();
                },
                List.of(new Setting.KeyAction(KEY_ID, "Lock key",
                        "Tap L over a slot to lock; hold L and move to a hotbar slot to link.",
                        "Open inv", SlotLock::openInventory)));
    }

    private static void openInventory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setScreen(new InventoryScreen(mc.player));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SlotLock::tick);
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                ScreenKeyboardEvents.beforeKeyRelease(screen).register((s, event) -> keyReleased(container, event.key()));
            }
        });
    }

    public static boolean on() {
        return enabled;
    }

    /**
     * Q is the only key outside a window that is guarded. The swap-hands key was too,
     * by dropping its packet - but this server never swaps on it, it opens the SkyBlock
     * menu, so all that guard did was stop the menu opening whenever a locked slot was
     * the one in your hand. Nothing moves on that key here, so there is nothing to stop.
     */
    public static boolean protectSelectedDrop() {
        Minecraft mc = Minecraft.getInstance();
        return enabled && mc.player != null && LOCKED.contains(mc.player.getInventory().getSelectedSlot());
    }

    // ── what is locked ────────────────────────────────────────────────────────

    /** The player inventory index behind this slot, or -1 for a chest's own slots. */
    private static int index(Slot slot) {
        return slot != null && slot.container instanceof Inventory ? slot.getContainerSlot() : -1;
    }

    public static boolean locked(Slot slot) {
        int i = index(slot);
        return enabled && i >= 0 && LOCKED.contains(i);
    }

    /** The hotbar slot this one is paired with, or -1. */
    public static int partner(Slot slot) {
        int i = index(slot);
        if (!enabled || i < 0) return -1;
        Integer other = BOUND.get(i);
        return other == null ? -1 : other;
    }

    private static void toggle(int i) {
        if (i < 0) return;
        toggleLock(LOCKED, BOUND, i);
        save();
    }

    static void toggleLock(Set<Integer> locks, Map<Integer, Integer> pairs, int i) {
        if (locks.remove(i)) unlink(pairs, i);
        else locks.add(i);
    }

    // ── pairing, by holding the key and dragging ──────────────────────────────

    /** A key-only gesture: press over one slot, move the pointer, release over another. */
    public static boolean keyPressed(AbstractContainerScreen<?> screen, int key) {
        if (!enabled || key != Keybinds.get(KEY_ID) || key < 0) return false;
        if (!keyHeld) start(screen);
        return true;
    }

    private static void keyReleased(AbstractContainerScreen<?> screen, int key) {
        if (key != Keybinds.get(KEY_ID) || key < 0 || !keyHeld) return;
        if (enabled && heldScreen == screen) finish(screen);
        clearGesture();
    }

    private static void tick(Minecraft mc) {
        if (!enabled || !(mc.screen instanceof AbstractContainerScreen<?> screen) || !mc.isWindowActive()) {
            clearGesture();
            return;
        }
        if (heldScreen != null && heldScreen != screen) clearGesture();
        // Mouse-button bindings have no keyboard events, so poll only those here.
        if (Keybinds.isMouse(Keybinds.get(KEY_ID))) {
            boolean down = held();
            if (down && !keyHeld) start(screen);
            else if (!down && keyHeld) {
                finish(screen);
                clearGesture();
            }
        }
    }

    private static void start(AbstractContainerScreen<?> screen) {
        keyHeld = true;
        heldScreen = screen;
        heldFrom = index(cursorSlot(screen));
        System.out.println("[Endsight] Slot Lock key start: " + heldFrom);
    }

    private static void finish(AbstractContainerScreen<?> screen) {
        int from = heldFrom, to = index(cursorSlot(screen));
        System.out.println("[Endsight] Slot Lock key release: " + from + " -> " + to);
        if (from < 0 || to < 0) return;
        if (from == to) {
            toggle(from);
            return;
        }
        boolean unbind = BOUND.getOrDefault(from, -1) == to;
        if (linkAndLock(BOUND, LOCKED, from, to)) {
            save();
            Toast.changed("Slot Lock", unbind ? "Link removed" : "Linked to hotbar " + (Math.min(from, to) + 1));
        } else {
            Toast.warn("Slot Lock", "Drag between inventory and hotbar slots");
        }
    }

    private static Slot cursorSlot(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        double x = mc.mouseHandler.getScaledXPos(mc.getWindow()) - screen.leftPos;
        double y = mc.mouseHandler.getScaledYPos(mc.getWindow()) - screen.topPos;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive() && x >= slot.x - 1 && x < slot.x + 17
                    && y >= slot.y - 1 && y < slot.y + 17) return slot;
        }
        return null;
    }

    private static boolean held() {
        int key = Keybinds.get(KEY_ID);
        return key != Keybinds.NONE && Keybinds.isDown(Minecraft.getInstance().getWindow(), key);
    }

    private static void clearGesture() {
        keyHeld = false;
        heldFrom = -1;
        heldScreen = null;
    }

    /** Keep the reverse links consistent when either endpoint is rebound or unbound. */
    static boolean togglePair(Map<Integer, Integer> pairs, int a, int b) {
        if (!pairable(a, b)) return false;
        if (pairs.getOrDefault(a, -1) == b && pairs.getOrDefault(b, -1) == a) {
            unlink(pairs, a);
            return true;
        }
        unlink(pairs, a);
        unlink(pairs, b);
        pairs.put(a, b);
        pairs.put(b, a);
        return true;
    }

    static boolean linkAndLock(Map<Integer, Integer> pairs, Set<Integer> locks, int a, int b) {
        if (!togglePair(pairs, a, b)) return false;
        locks.add(a);
        locks.add(b);
        return true;
    }

    private static boolean pairable(int a, int b) {
        return (a >= 0 && a <= HOTBAR_END && b > HOTBAR_END && b <= MAIN_END)
                || (b >= 0 && b <= HOTBAR_END && a > HOTBAR_END && a <= MAIN_END);
    }

    private static void unlink(Map<Integer, Integer> pairs, int index) {
        Integer other = pairs.remove(index);
        if (other != null) pairs.remove(other);
    }

    // ── the one place a move can happen ───────────────────────────────────────

    /**
     * Whether the window should be stopped from acting on this click.
     *
     * A locked slot refuses everything. A paired slot refuses only the shift-click, and
     * sends a hotbar swap in its place - which is the whole point of the pair: one key
     * chord puts your sword in your hand and the pickaxe back where it lives.
     */
    public static boolean intercept(AbstractContainerScreen<?> screen, Slot slot, int button, ContainerInput input) {
        if (!enabled || slot == null) return false;
        int from = index(slot);
        int other = partner(slot);
        if (input == ContainerInput.QUICK_MOVE && other >= 0) {
            int hotbar = Math.min(from, other);
            int main = Math.max(from, other);
            Slot target = playerSlot(screen, main);
            Minecraft mc = Minecraft.getInstance();
            if (target == null || mc.gameMode == null || mc.player == null) return true;
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, target.index, hotbar,
                    ContainerInput.SWAP, mc.player);
            return true;
        }
        if (locked(slot)) return true;
        if (input == ContainerInput.SWAP && button >= 0 && button <= OFFHAND && LOCKED.contains(button)) return true;
        if (input == ContainerInput.PICKUP_ALL && collectsLocked(screen)) return true;
        return input == ContainerInput.QUICK_MOVE && wouldMoveIntoLocked(screen, slot);
    }

    private static Slot playerSlot(AbstractContainerScreen<?> screen, int index) {
        for (Slot slot : screen.getMenu().slots) if (index(slot) == index) return slot;
        return null;
    }

    private static boolean collectsLocked(AbstractContainerScreen<?> screen) {
        ItemStack carried = screen.getMenu().getCarried();
        if (carried.isEmpty()) return false;
        for (Slot slot : screen.getMenu().slots) {
            if (locked(slot) && ItemStack.isSameItemSameComponents(carried, slot.getItem())) return true;
        }
        return false;
    }

    private static boolean wouldMoveIntoLocked(AbstractContainerScreen<?> screen, Slot source) {
        ItemStack item = source.getItem();
        if (item.isEmpty()) return false;

        List<Slot> destinations = new ArrayList<>();
        if (screen.getMenu() instanceof InventoryMenu) {
            if (source.index >= 9 && source.index < 45) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    EquipmentSlot equipment = mc.player.getEquipmentSlotForItem(item);
                    int equipmentIndex = equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                            ? 8 - equipment.getIndex() : equipment == EquipmentSlot.OFFHAND ? 45 : -1;
                    if (equipmentIndex >= 0) {
                        Slot target = screen.getMenu().getSlot(equipmentIndex);
                        if (target.getItem().isEmpty() && locked(target)) return true;
                    }
                }
            }
            int start = source.index >= 9 && source.index < 36 ? 36 :
                    source.index >= 36 && source.index < 45 ? 9 : 9;
            int end = source.index >= 9 && source.index < 36 ? 45 :
                    source.index >= 36 && source.index < 45 ? 36 : 45;
            boolean reverse = source.index == 0;
            for (int i = reverse ? end - 1 : start; reverse ? i >= start : i < end; i += reverse ? -1 : 1) {
                destinations.add(screen.getMenu().getSlot(i));
            }
        } else if (index(source) < 0) {
            // Container output goes to the player inventory, hotbar first in vanilla menus.
            for (int i = screen.getMenu().slots.size() - 1; i >= 0; i--) {
                Slot target = screen.getMenu().slots.get(i);
                if (index(target) >= 0) destinations.add(target);
            }
        } else {
            // Moving a player item into a container cannot fill another player lock.
            return false;
        }

        int remaining = item.getCount();
        if (item.isStackable()) for (Slot target : destinations) {
            ItemStack existing = target.getItem();
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(item, existing)) continue;
            int room = target.getMaxStackSize(item) - existing.getCount();
            if (room <= 0) continue;
            if (locked(target)) return true;
            remaining -= room;
            if (remaining <= 0) return false;
        }
        for (Slot target : destinations) {
            if (!target.getItem().isEmpty() || !target.mayPlace(item)) continue;
            return locked(target);
        }
        return false;
    }

    // ── on screen ─────────────────────────────────────────────────────────────

    /** A padlock on a locked slot and an accent corner when it is also linked. */
    public static void overSlot(GuiGraphicsExtractor g, Slot slot) {
        if (!enabled) return;
        if (locked(slot)) {
            int c = Theme.neg();
            int x = slot.x + 1, y = slot.y + 1;
            // shackle
            Draw.rect(g, x + 1, y, 3, 1, c);
            Draw.rect(g, x, y + 1, 1, 2, c);
            Draw.rect(g, x + 4, y + 1, 1, 2, c);
            // body, as a ring so the slot still shows through it
            Draw.rect(g, x - 1, y + 3, 7, 1, c);
            Draw.rect(g, x - 1, y + 7, 7, 1, c);
            Draw.rect(g, x - 1, y + 4, 1, 3, c);
            Draw.rect(g, x + 5, y + 4, 1, 3, c);
        }
        if (partner(slot) < 0) return;
        int c = Theme.accent();
        Draw.rect(g, slot.x + 13, slot.y, 3, 1, c);
        Draw.rect(g, slot.x + 15, slot.y, 1, 3, c);
        Draw.rect(g, slot.x + 13, slot.y + 15, 3, 1, c);
        Draw.rect(g, slot.x + 15, slot.y + 13, 1, 3, c);
    }

    /** Draw once per pair, behind the items, in the screen's translated slot coordinates. */
    public static void links(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!enabled) return;
        int color = Theme.accent();
        for (Map.Entry<Integer, Integer> entry : BOUND.entrySet()) {
            int hotbar = entry.getKey(), main = entry.getValue();
            if (hotbar < 0 || hotbar > HOTBAR_END) continue;
            Slot a = playerSlot(screen, main), b = playerSlot(screen, hotbar);
            if (a == null || b == null || !a.isActive() || !b.isActive()
                    || !LOCKED.contains(main) || !LOCKED.contains(hotbar)) continue;
            tracer(g, a, b, color);
        }
        if (keyHeld && heldScreen == screen && heldFrom >= 0) {
            Slot from = playerSlot(screen, heldFrom);
            Slot target = cursorSlot(screen);
            if (from != null && target != null && pairable(heldFrom, index(target))) {
                tracer(g, from, target, color);
            }
        }
    }

    private static void tracer(GuiGraphicsExtractor g, Slot a, Slot b, int color) {
        Slot upper = a.y <= b.y ? a : b;
        Slot lower = upper == a ? b : a;
        int x0 = upper.x + 8, y0 = upper.y + 17;
        int x1 = lower.x + 8, y1 = lower.y - 1;
        // A dark one-pixel shadow keeps the continuous accent stroke readable over glass.
        line(g, x0 + 1, y0, x1 + 1, y1, 0xA0000000);
        line(g, x0, y0, x1, y1, Draw.alpha(color, 0.9f));
        Draw.rect(g, x0 - 1, y0, 3, 1, color);
        Draw.rect(g, x1 - 1, y1, 3, 1, color);
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            Draw.rect(g, x0, y0, 1, 1, color);
            if (x0 == x1 && y0 == y1) return;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    // ── on disk ───────────────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("slot-locks.txt");
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        // L by default, so the feature works on first launch instead of doing nothing until
        // someone finds the keybind chip. An existing binding is left alone.
        if (Keybinds.get(KEY_ID) == Keybinds.NONE) Keybinds.set(KEY_ID, org.lwjgl.glfw.GLFW.GLFW_KEY_L);
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals("lock")) {
                    int slot = Integer.parseInt(parts[1]);
                    if (slot >= 0 && slot <= OFFHAND) LOCKED.add(slot);
                }
                else if (parts.length == 3 && parts[0].equals("bind")) {
                    int a = Integer.parseInt(parts[1]), b = Integer.parseInt(parts[2]);
                    if (pairable(a, b)) {
                        unlink(BOUND, a);
                        unlink(BOUND, b);
                        BOUND.put(a, b);
                        BOUND.put(b, a);
                        LOCKED.add(a);
                        LOCKED.add(b);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read slot locks: " + e);
        }
    }

    private static void save() {
        List<String> out = new ArrayList<>();
        for (int i : LOCKED) out.add("lock " + i);
        Set<Integer> done = new LinkedHashSet<>();
        BOUND.forEach((a, b) -> {
            if (done.contains(a) || done.contains(b)) return;
            done.add(a);
            done.add(b);
            out.add("bind " + a + " " + b);
        });
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write slot locks: " + e);
        }
    }
}
