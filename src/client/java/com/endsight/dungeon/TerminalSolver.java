package com.endsight.dungeon;

import com.endsight.hud.Area;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Visual terminal hints. Each physical mouse click on a terminal sends exactly one click. */
public final class TerminalSolver {
    private static boolean enabled = true;
    private static boolean blockWrongClicks = true;
    private static String layout = "Compact";
    private static AbstractContainerScreen<?> current;
    private static int window = -1;
    private static final Set<Integer> selected = new HashSet<>();
    /** Clicks sent that the server has not answered yet, oldest first. */
    private record Sent(int slot, long at) { }
    private static final List<Sent> sent = new ArrayList<>();
    // The server answers a terminal click in 110-180 ms. One unanswered after this was
    // dropped (a lag spike, a click while the board reset), so its hint comes back.
    private static final long ANSWER_MS = 1000;
    private static List<ObservedSlot> plannedSlots = List.of();
    private static String plannedTitle;
    private static TerminalLogic.Plan cachedPlan;
    private static Object checkWorld;
    private static boolean quickCheck;
    private TerminalSolver() { }

    public static Module module() {
        return new Module("dungeon.terminals", "Terminal Solvers", "Compact menu hints and Simon Says button guidance.",
                "Dungeons", () -> enabled, v -> enabled = v, List.of(
                new Setting.Choice("Layout", "Compact grid or highlights over the normal chest; every click is yours.",
                        List.of("Compact", "Normal chest"), () -> layout, v -> layout = v),
                new Setting.Toggle("Block Wrong Clicks", "Reject non-solution and duplicate clicks while a terminal is open.",
                        () -> blockWrongClicks, v -> blockWrongClicks = v),
                new Setting.Toggle("Simon Says", "Highlight the next button on the in-world light board.",
                        SimonSays::enabled, SimonSays::setEnabled)));
    }

    static boolean enabledForSimon() { return enabled; }


    /**
     * Sends one click on a terminal slot, and changes nothing on this side.
     *
     * Vanilla's own click predicts the result first: it lifts the pane onto the cursor and
     * leaves the slot empty until the server's answer puts it back, 110-180 ms later. That
     * was the flicker on every click, and it emptied the board under the solver, which read
     * "Waiting for numbered panes" between every Click in Order click. Worse, letting go of
     * the mouse while carrying it dropped the pane into whatever slot was under the cursor:
     * the 2026-10-23 22:35 recording has a purple pane put in slot 47, an Ancient Maxor's
     * Chestplate lifted out of the inventory, and the server reshuffling the purple board
     * after the stray clicks. This sends the same packet with the menu as it stands, so the
     * server does the one click and its slot updates are the only change anyone sees. The
     * click is remembered so its hint disappears at once instead of a ping later.
     */
    public static void click(int containerId, int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (current == null || window != containerId || connection == null || mc.player == null) return;
        var menu = current.getMenu();
        connection.send(new ServerboundContainerClickPacket(containerId, menu.getStateId(), (short) slot, (byte) button,
                ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
        sent.add(new Sent(slot, System.currentTimeMillis()));
    }

    public static void init() {
        SimonSays.init();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim();
            if (line.startsWith("QUICK CHECK!")) { quickCheck = true; checkWorld = Minecraft.getInstance().level; }
            if (line.startsWith("CHECK PASSED!") || line.startsWith("CHECK FAILED!")) quickCheck = false;
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(container, g, mx, my));
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (!eligible(container)) return true;
                TerminalLogic.Plan plan = shown(container);
                boolean compact = compactShown(plan);
                int slot = compact ? frame(container, plan.type()).slot(click.x(), click.y()) : nativeSlot(container, click.x(), click.y());
                TerminalLogic.Hint hint = target(plan, slot, click.button());
                if (hint != null) {
                    click(container.getMenu().containerId, slot, hint.button() == TerminalLogic.Button.RIGHT ? 1 : 0);
                    return false;
                }
                if (compact || blockWrongClicks) return false;
                // Blocking off, normal chest: a wrong click still goes, but the same way, so
                // nothing lands on the cursor. Clicks in your own inventory stay vanilla.
                if (slot >= 0 && (click.button() == 0 || click.button() == 1)) {
                    click(container.getMenu().containerId, slot, click.button());
                    return false;
                }
                return true;
            });
            // Vanilla places a carried item on release, under wherever the cursor went - in the
            // compact grid, a hidden chest slot. Nothing is ever carried now, but a terminal has
            // no use for a release or a drag either, so neither reaches it.
            ScreenMouseEvents.allowMouseRelease(screen).register((s, click) -> !eligible(container));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, click, dx, dy) -> !eligible(container));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!compactActive(s)) return true;
                if (event.key() == 256) return true;
                if (mc.options.keyInventory.matches(event)) s.onClose();
                // The hidden native grid must never receive drop/number/quick-craft hotkeys.
                return false;
            });
            ScreenEvents.remove(screen).register(s -> {
                if (s == current) {
                    current = null; window = -1; sent.clear(); selected.clear(); clearPlan();
                }
            });
        });
    }

    private static boolean eligible(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (checkWorld != mc.level) quickCheck = false;
        return enabled && !quickCheck && mc.player != null && mc.screen == screen && DungeonContext.active()
                && TerminalLogic.type(screen.getTitle().getString()) != TerminalLogic.Type.UNKNOWN;
    }

    public static boolean compactActive(Screen screen) {
        return layout.equals("Compact") && screen instanceof AbstractContainerScreen<?> container
                && eligible(container) && compactShown(plan(container));
    }

    private static boolean compactShown(TerminalLogic.Plan plan) {
        // A recognized terminal owns its compact surface from the first frame.
        // Waiting for a ready plan exposed vanilla for a frame after each server
        // re-open, and local click predictions could briefly make it unready again.
        return layout.equals("Compact");
    }

    /** Called only after an actual server slot update, never after a local prediction. */
    public static void serverSlotUpdated(int containerId, int slot) {
        if (current == null || window != containerId) return;
        cachedPlan = null;
        // Melody's button never changes on a hit; the track does. Any update means the
        // click has been judged, and the next alignment may be clicked.
        if (TerminalLogic.type(current.getTitle().getString()) == TerminalLogic.Type.MELODY) { sent.clear(); return; }
        for (Iterator<Sent> it = sent.iterator(); it.hasNext(); ) {
            if (it.next().slot() != slot) continue;
            it.remove(); selected.add(slot);
            break;
        }
    }

    public static void serverContentUpdated(int containerId) {
        if (current == null || window != containerId) return;
        cachedPlan = null;
        for (Sent click : sent) selected.add(click.slot());
        sent.clear();
    }

    /** The plan as the player should see it: the server's board, less the clicks already sent. */
    private static TerminalLogic.Plan shown(AbstractContainerScreen<?> screen) {
        TerminalLogic.Plan plan = plan(screen);
        long now = System.currentTimeMillis();
        sent.removeIf(click -> now - click.at() > ANSWER_MS);
        if (sent.isEmpty() || !plan.ready()) return plan;
        if (plan.type() == TerminalLogic.Type.MELODY) return new TerminalLogic.Plan(plan.type(), List.of(), "Clicked", true);
        List<TerminalLogic.Hint> hints = new ArrayList<>();
        for (TerminalLogic.Hint hint : plan.hints()) {
            int clicks = hint.clicks() - (int) sent.stream().filter(click -> click.slot() == hint.slot()).count();
            if (clicks > 0) hints.add(new TerminalLogic.Hint(hint.slot(), clicks, hint.button()));
        }
        String status = hints.isEmpty() ? "Complete" : plan.type() == TerminalLogic.Type.ORDER
                ? "Next: " + screen.getMenu().slots.get(hints.getFirst().slot()).getItem().getCount() : plan.status();
        return new TerminalLogic.Plan(plan.type(), hints, status, true);
    }

    /** The hint a click on this slot would carry out, or null if it is not one. Button -1 is any. */
    private static TerminalLogic.Hint target(TerminalLogic.Plan plan, int slot, int button) {
        if (!plan.ready() || slot < 0 || button > 1) return null;
        for (int i = 0; i < plan.hints().size(); i++) {
            TerminalLogic.Hint hint = plan.hints().get(i);
            if (hint.slot() != slot) continue;
            if (plan.type() == TerminalLogic.Type.ORDER && i != 0) return null;
            // Same colour turns each way; the other button undoes a step instead of making one.
            if (plan.type() == TerminalLogic.Type.RUBIX && button >= 0
                    && (button == 1) != (hint.button() == TerminalLogic.Button.RIGHT)) return null;
            return hint;
        }
        return null;
    }

    private record ObservedSlot(int index, ItemStack stack) { }

    private static void clearPlan() {
        plannedSlots = List.of(); plannedTitle = null; cachedPlan = null;
    }

    private static boolean sameSlots(AbstractContainerScreen<?> screen, Minecraft mc) {
        int observed = 0;
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            var slot = screen.getMenu().slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            if (observed >= plannedSlots.size()) return false;
            ObservedSlot previous = plannedSlots.get(observed++);
            ItemStack stack = slot.getItem();
            if (previous.index() != i || previous.stack().getCount() != stack.getCount()
                    || !ItemStack.isSameItemSameComponents(previous.stack(), stack)) return false;
        }
        return observed == plannedSlots.size();
    }

    private static TerminalLogic.Plan plan(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (current != screen || window != screen.getMenu().containerId) {
            current = screen; window = screen.getMenu().containerId; selected.clear(); sent.clear();
            clearPlan();
        }
        String title = screen.getTitle().getString();
        // Background suppression, drawing and input can all ask for the plan in the
        // same frame. Reuse it while the actual stacks match, rather than throttling:
        // Melody and local click predictions must invalidate immediately as well as
        // server packets, including full-container packets without a per-slot hook.
        if (cachedPlan != null && title.equals(plannedTitle) && sameSlots(screen, mc)) return cachedPlan;
        List<TerminalLogic.Cell> cells = new ArrayList<>();
        List<ObservedSlot> observed = new ArrayList<>();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            var slot = screen.getMenu().slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            var stack = slot.getItem();
            observed.add(new ObservedSlot(i, stack.copy()));
            cells.add(new TerminalLogic.Cell(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getHoverName().getString(), stack.getCount(), stack.hasFoil()));
        }
        plannedSlots = List.copyOf(observed);
        plannedTitle = title;
        cachedPlan = TerminalLogic.solve(title, cells.size(), cells, selected);
        return cachedPlan;
    }

    record Frame(int x, int y, int columns, int rows, int firstColumn, int firstRow) {
        static final int CELL = 24;
        int slot(double mx, double my) {
            int cx = (int)Math.floor((mx - x) / CELL), cy = (int)Math.floor((my - y) / CELL);
            if (mx < x || my < y || cx < 0 || cy < 0 || cx >= columns || cy >= rows
                    || (mx - x) % CELL >= 22 || (my - y) % CELL >= 22) return -1;
            return (cy + firstRow) * 9 + cx + firstColumn;
        }
        int sx(int slot) { return x + (slot % 9 - firstColumn) * CELL; }
        int sy(int slot) { return y + (slot / 9 - firstRow) * CELL; }
    }

    private static Frame frame(AbstractContainerScreen<?> screen, TerminalLogic.Type type) {
        int cols = type == TerminalLogic.Type.RUBIX ? 3 : 7;
        int rows = switch(type) { case ORDER -> 2; case COLORS, MELODY -> 4; default -> 3; };
        return new Frame((screen.width - cols * 24) / 2, (screen.height - rows * 24) / 2,
                cols, rows, type == TerminalLogic.Type.RUBIX ? 3 : 1, type == TerminalLogic.Type.MELODY ? 0 : 1);
    }

    private static int nativeSlot(AbstractContainerScreen<?> screen, double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            var slot = screen.getMenu().slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            int x = screen.leftPos + slot.x, y = screen.topPos + slot.y;
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) return i;
        }
        return -1;
    }

    private static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        if (!eligible(screen)) return;
        Minecraft mc = Minecraft.getInstance();
        TerminalLogic.Plan plan = shown(screen);
        boolean compact = compactShown(plan);
        Frame f = frame(screen, plan.type());
        String help = plan.type() == TerminalLogic.Type.RUBIX ? "L = left click  ·  R = right click" : "Click highlighted tiles  ·  Esc closes";
        String title = Zealots.strip(screen.getTitle().getString());
        int panelWidth = Math.max(f.columns() * 24 + 22, Math.max(mc.font.width(title), Math.max(mc.font.width(help), mc.font.width(plan.status()))) + 24);
        int panelLeft = f.x() + (f.columns() * 24 - panelWidth) / 2;
        if (compact) {
            Draw.rect(g, 0, 0, screen.width, screen.height, Draw.alpha(Theme.bg(), .3f));
            Draw.roundedRect(g, panelLeft, f.y() - 34, panelWidth, f.rows() * 24 + 70, 4, Draw.alpha(Theme.bg(), .9f));
            Draw.text(g, mc.font, title, panelLeft + 12, f.y() - 25, 0xFFFFAA00);
            if (plan.type() == TerminalLogic.Type.MELODY) {
                for (int r = 0; r < f.rows(); r++) for (int c = 0; c < f.columns(); c++) {
                    int slot = (r + f.firstRow()) * 9 + c + f.firstColumn();
                    var stack = screen.getMenu().slots.get(slot).getItem();
                    Draw.roundedRect(g, f.sx(slot), f.sy(slot), 22, 22, 2, Theme.surface());
                    g.item(stack, f.sx(slot) + 3, f.sy(slot) + 3);
                }
            }
        }
        // On the normal chest the clicked pane still shows its old colour until the server
        // answers; a green fill marks it done meanwhile. The compact grid just drops the hint.
        if (!compact) for (Sent click : sent) {
            var slot = screen.getMenu().slots.get(click.slot());
            Draw.rect(g, screen.leftPos + slot.x, screen.topPos + slot.y, 16, 16, Draw.alpha(0xFF55FF55, .45f));
        }
        for (int i = 0; i < plan.hints().size(); i++) {
            TerminalLogic.Hint hint = plan.hints().get(i);
            var slot = screen.getMenu().slots.get(hint.slot());
            int x = compact ? f.sx(hint.slot()) : screen.leftPos + slot.x;
            int y = compact ? f.sy(hint.slot()) : screen.topPos + slot.y;
            int size = compact ? 22 : 16;
            int colour = plan.type() == TerminalLogic.Type.ORDER && i > 0 ? (i == 1 ? 0xFFFFFF55 : 0xFFAAAAAA) : 0xFF55FF55;
            Draw.roundedOutline(g, x, y, size, size, 2, colour, Draw.alpha(colour, .22f));
            if (compact && plan.type() != TerminalLogic.Type.MELODY) {
                String text = plan.type() == TerminalLogic.Type.RUBIX ? (hint.button() == TerminalLogic.Button.LEFT ? "L" : "R") + hint.clicks()
                        : plan.type() == TerminalLogic.Type.ORDER ? Integer.toString(slot.getItem().getCount()) : "";
                if (!text.isEmpty()) Draw.text(g, mc.font, text, x + (22 - mc.font.width(text)) / 2, y + 7, Theme.text());
            }
            if (compact && f.slot(mx, my) == hint.slot()) Draw.roundedOutline(g, x, y, size, size, 2, Theme.text(), Draw.alpha(colour, .4f));
        }
        Draw.text(g, mc.font, plan.status(), compact ? panelLeft + 12 : screen.leftPos, compact ? f.y() + f.rows() * 24 + 5 : Math.max(2, screen.topPos - 12),
                plan.ready() ? Theme.pos() : Theme.muted());
        if (compact) Draw.text(g, mc.font, help, panelLeft + 12, f.y() + f.rows() * 24 + 18, Theme.muted());
    }
}
