package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.EndsightScreen;
import com.endsight.ui.SettingsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One explicit request owns the server menu until acknowledgement or interruption. */
final class MenuBindRequest {
    private MenuBindRequest() { }

    enum Kind { WAIT, CLICK, PAGE, DONE, FAIL }
    record Decision(Kind kind, int slot, String message) {
        static Decision waitForItems() { return new Decision(Kind.WAIT, -1, ""); }
        static Decision click(int slot) { return new Decision(Kind.CLICK, slot, ""); }
        static Decision page(int slot) { return new Decision(Kind.PAGE, slot, ""); }
        static Decision done() { return new Decision(Kind.DONE, -1, ""); }
        static Decision fail(String message) { return new Decision(Kind.FAIL, -1, message); }
    }
    interface Selection {
        Decision decide(AbstractContainerScreen<?> screen, boolean selectionSent);
    }

    private static boolean initialized;
    private static Request active;
    private static final long TIMEOUT_MS = 5_000;
    private static final long STABLE_MS = 150;
    /**
     * How long a sent click waits to show up in the menu before it is closed anyway. Not a
     * failure: the server's own chat line says how it went, and a target on a page the menu
     * did not come back to can never show up at all.
     */
    private static final long CONFIRM_MS = 1_500;

    static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(MenuBindRequest::tick);
        // Nothing the player presses stops a request; only closing the menu does. Any key,
        // click or scroll used to, and the wardrobe's wait is a whole second, so carrying on
        // walking or fighting after a press lost the swap: the log from his first real test is
        // mostly "cancelled by your input" between the swaps that got through. Those guarded
        // nothing, since no click is ever sent with something on the cursor.
    }

    static boolean start(String name, String command, Predicate<AbstractContainerScreen<?>> menu,
                         long openingDelay, Selection selection) {
        return start(name, command, menu, openingDelay, selection, false);
    }

    /** Like {@link #start}, but a request made from our own page ends back on that page. */
    static boolean refresh(String name, String command, Predicate<AbstractContainerScreen<?>> menu, Selection selection) {
        return start(name, command, menu, 0, selection, true);
    }

    private static boolean start(String name, String command, Predicate<AbstractContainerScreen<?>> menu,
                                 long openingDelay, Selection selection, boolean returnHere) {
        Minecraft mc = Minecraft.getInstance();
        // A second press while one runs, which is only a fast finger. It used to say so in chat.
        if (active != null) return false;
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.gameMode == null) return false;
        boolean ownSettings = mc.screen instanceof EndsightScreen || mc.screen instanceof SettingsScreen;
        if (!ownSettings && mc.screen != null && (!(mc.screen instanceof AbstractContainerScreen<?> screen) || !menu.test(screen))) {
            tell("Close the current screen before using " + name + ".");
            return false;
        }
        if (!mc.player.containerMenu.getCarried().isEmpty()) { tell("Empty your cursor before using " + name + "."); return false; }
        Screen back = ownSettings && returnHere ? mc.screen : null;
        if (ownSettings) mc.setScreen(null);
        active = new Request(name, menu, openingDelay, selection, mc, back);
        if (mc.screen == null) mc.getConnection().sendCommand(command);
        return true;
    }

    /** Consume assigned menu keys before vanilla can interpret a number, Q, or inventory key. */
    static void menuKeys(Predicate<AbstractContainerScreen<?>> menu, Supplier<Map<String, Runnable>> actions, Set<Integer> held) {
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container) || !menu.test(container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                for (var action : actions.get().entrySet()) {
                    if (Keybinds.get(action.getKey()) != event.key()) continue;
                    if (held.add(event.key())) action.getValue().run();
                    return false;
                }
                return true;
            });
            ScreenKeyboardEvents.beforeKeyRelease(screen).register((s, event) -> held.remove(event.key()));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                int key = Keybinds.mouse(event.button());
                for (var action : actions.get().entrySet()) {
                    if (Keybinds.get(action.getKey()) != key) continue;
                    if (held.add(key)) action.getValue().run();
                    return false;
                }
                return true;
            });
        });
    }

    /** Which of these binds are physically down, so a menu key fires once per press. */
    static void heldKeys(Minecraft mc, Collection<String> ids, Set<Integer> held) {
        held.clear();
        for (String id : ids) {
            int key = Keybinds.get(id);
            if (key != Keybinds.NONE && Keybinds.isDown(mc.getWindow(), key)) held.add(key);
        }
    }

    /**
     * True once per change, after a menu's stacks have held still for a moment: a server fills
     * one over several packets.
     *
     * Compares references first, because a packet replaces a stack and an idle menu sends
     * none, so a menu left open costs one pass over its slots and parses nothing. The fuller
     * compare is there for a server that resends an unchanged stack, which would otherwise
     * keep this from ever settling.
     *
     * Counts are kept apart and the cursor is checked because a click does not replace
     * anything: the client empties the same stack object in place while it waits for the
     * server. Going by references alone read that page mid-click and dropped the pet just
     * clicked from the list, which the game test caught as a missing Warden tile.
     */
    static final class Settled {
        private static final ItemStack[] NONE = new ItemStack[0];
        private ItemStack[] seen = NONE;
        private int[] counts = new int[0];
        private long since;
        private boolean pending;
        boolean after(AbstractContainerScreen<?> screen, long wait, long now) {
            var slots = screen.getMenu().slots;
            boolean changed = seen.length != slots.size() || !screen.getMenu().getCarried().isEmpty();
            if (seen.length != slots.size()) { seen = new ItemStack[slots.size()]; counts = new int[slots.size()]; }
            for (int i = 0; i < seen.length; i++) {
                ItemStack stack = slots.get(i).getItem(), old = seen[i];
                int count = stack.getCount();
                if (stack == old && count == counts[i]) continue;
                if (old == null || count != counts[i] || !ItemStack.isSameItemSameComponents(stack, old)) changed = true;
                seen[i] = stack;
                counts[i] = count;
            }
            if (changed) { since = now; pending = true; return false; }
            if (!pending || now - since < wait) return false;
            pending = false;
            return true;
        }
        void reset() { seen = NONE; pending = false; }
    }

    static void cancel(String name) {
        if (active != null && active.name.equals(name)) active = null;
    }

    static boolean expired(long started, long now) { return now - started >= TIMEOUT_MS; }
    static boolean ready(long arrived, long stable, long delay, long now) {
        return now - arrived >= delay && now - stable >= STABLE_MS;
    }

    private static void tick(Minecraft mc) {
        Request r = active;
        if (r == null) return;
        long now = System.currentTimeMillis();
        if (expired(r.started, now)) { stop("Menu bind timed out; no further clicks were sent."); return; }
        if (mc.player != r.player || mc.level != r.level || mc.getConnection() != r.connection || mc.gameMode == null) {
            active = null; return;
        }
        boolean confirming = r.selectionSent && now - r.sent < CONFIRM_MS;
        if (mc.screen == null) {
            // Reopening a menu is a close and then an open, and a tick can land between the
            // two. Ending there would leave the reopened menu up with nobody to close it.
            if (r.screen != null && !r.pagePending && !confirming) finish(mc, r);
            return;
        }
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || !r.menu.test(screen)
                || screen.getMenu() != mc.player.containerMenu) {
            stop("Menu bind cancelled: a different screen opened."); return;
        }
        if (!screen.getMenu().getCarried().isEmpty()) {
            // Vanilla may predict picking up a fake menu icon until the server rejects that
            // inventory move, ours or a stray click of the player's. Wait for reconciliation;
            // never act again or close the menu with a predicted item on the cursor. A real
            // item there just runs the request out of time with nothing clicked.
            return;
        }
        if (r.screen != screen) {
            // Dragsim answers a pet click by opening the menu again, the same as a page arrow,
            // so a new window after a click is the answer and not somebody else's menu. Stopping
            // here left it open with the pet already changed: the log shows "Activated" and the
            // cancel in the same second, and the test server only ever edited the menu in place.
            if (r.screen != null && !r.pagePending && !r.selectionSent) { stop("Menu bind cancelled: the menu changed."); return; }
            r.screen = screen;
            r.arrived = now;
            r.stable = now;
            r.snapshot = List.of();
        }
        if (r.selectionSent && !confirming) { finish(mc, r); return; }
        List<ItemStack> items = serverItems(mc, screen);
        if (!same(items, r.snapshot)) {
            r.snapshot = items.stream().map(ItemStack::copy).toList();
            r.stable = now;
            r.pagePending = false;
        }
        // The opening delay is for the first look at a menu. Held against the answer too, one
        // that reopens would sit there for the wardrobe's whole second again, and held against
        // each page it added a quarter second per page to reaching a pet far down a long list.
        if (!ready(r.arrived, r.stable, r.selectionSent || r.paged ? 0 : r.delay, now) || r.pagePending) return;
        Decision decision = r.selection.decide(screen, r.selectionSent);
        if (decision.kind == Kind.WAIT) return;
        if (decision.kind == Kind.FAIL) { stop(decision.message); return; }
        if (decision.kind == Kind.DONE) { finish(mc, r); return; }
        if (r.selectionSent) return;
        if (decision.slot < 0 || decision.slot >= screen.getMenu().slots.size()) {
            stop("Menu bind cancelled: invalid menu entry."); return;
        }
        var slot = screen.getMenu().slots.get(decision.slot);
        if (slot.container == mc.player.getInventory() || !slot.hasItem()) {
            stop("Menu bind cancelled: entry is not in the server menu."); return;
        }
        if (decision.kind == Kind.PAGE) {
            r.pagePending = true;
            r.paged = true;
            r.stable = now;
            // Each page earns its own time: a long pet list would time out, not fail.
            r.started = now;
        } else {
            r.selectionSent = true;
            r.sent = now;
            // Its own clock, like a page: a slow menu must not time out while confirming.
            r.started = now;
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, decision.slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private static void stop(String reason) { active = null; tell(reason); }
    /** Close what the request opened and hand back the page it started from. */
    private static void finish(Minecraft mc, Request r) {
        active = null;
        // Never close a menu the player was already using, or a replacement screen.
        if (r.opened && mc.screen == r.screen && mc.player.containerMenu == r.screen.getMenu())
            mc.player.closeContainer();
        if (r.back != null) mc.setScreen(r.back);
    }
    static void tell(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addClientSystemMessage(Component.literal("[Endsight] " + text));
    }
    /**
     * Text as a person reads it. Dragsim draws its rarity badge from a private-use font, so
     * a lore line is really a bar of glyphs around LEGENDARY and COMBAT PET. Those glyphs
     * exist only in the server's font: copied into our own text they were tofu 140 boxes
     * wide, found from a screenshot of the old favourites page and the labels it had saved.
     */
    static String plain(String text) {
        return text.replaceAll("§[0-9A-FK-ORa-fk-or]", "").replaceAll("[\\uE000-\\uF8FF]+", " ").trim();
    }
    static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        return lore == null ? List.of() : lore.lines().stream().map(c -> plain(c.getString())).toList();
    }
    static boolean serverSlot(Minecraft mc, net.minecraft.world.inventory.Slot slot) {
        return mc.player != null && slot.container != mc.player.getInventory();
    }
    private static List<ItemStack> serverItems(Minecraft mc, AbstractContainerScreen<?> screen) {
        List<ItemStack> items = new ArrayList<>();
        for (var slot : screen.getMenu().slots) if (serverSlot(mc, slot)) items.add(slot.getItem());
        return items;
    }
    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).getCount() != b.get(i).getCount() || !ItemStack.isSameItemSameComponents(a.get(i), b.get(i))) return false;
        return true;
    }
    private static final class Request {
        final String name;
        final Predicate<AbstractContainerScreen<?>> menu;
        final Selection selection;
        final long delay;
        long started;
        final Object player, level, connection;
        final boolean opened;
        final Screen back;
        AbstractContainerScreen<?> screen;
        long arrived, stable, sent;
        List<ItemStack> snapshot = List.of();
        boolean selectionSent, pagePending, paged;
        Request(String name, Predicate<AbstractContainerScreen<?>> menu, long delay, Selection selection, Minecraft mc, Screen back) {
            this.name = name; this.menu = menu; this.delay = delay; this.selection = selection; this.back = back;
            started = System.currentTimeMillis();
            player = mc.player; level = mc.level; connection = mc.getConnection();
            opened = mc.screen == null;
        }
    }
}
