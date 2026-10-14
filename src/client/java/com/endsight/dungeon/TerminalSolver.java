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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Visual terminal hints. A compact-grid click forwards exactly one physical mouse click. */
public final class TerminalSolver {
    private static boolean enabled = true;
    private static String layout = "Compact";
    private static AbstractContainerScreen<?> current;
    private static int window = -1;
    private static final Set<Integer> selected = new HashSet<>(), pending = new HashSet<>();
    private static List<ObservedSlot> plannedSlots = List.of();
    private static String plannedTitle;
    private static TerminalLogic.Plan cachedPlan;
    private static Object checkWorld;
    private static boolean quickCheck;
    private TerminalSolver() { }

    public static Module module() {
        return new Module("dungeon.terminals", "Terminal Solvers", "Compact manual terminal hints and live timing indicators.",
                "Dungeons", () -> enabled, v -> enabled = v, List.of(new Setting.Choice("Layout",
                "Compact grid or highlights over the normal chest; every click is yours.",
                List.of("Compact", "Normal chest"), () -> layout, v -> layout = v)));
    }

    public static void init() {
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
                TerminalLogic.Plan plan = plan(container);
                boolean compact = layout.equals("Compact") && plan.ready();
                int slot = compact ? frame(container, plan.type()).slot(click.x(), click.y()) : nativeSlot(container, click.x(), click.y());
                boolean valid = plan.hints().stream().anyMatch(hint -> hint.slot() == slot);
                if (plan.type() == TerminalLogic.Type.ORDER && !plan.hints().isEmpty()) valid = slot == plan.hints().getFirst().slot();
                if (valid && (click.button() == 0 || click.button() == 1)) {
                    pending.add(slot);
                    if (compact && mc.gameMode != null)
                        mc.gameMode.handleContainerInput(container.getMenu().containerId, slot, click.button(), ContainerInput.PICKUP, mc.player);
                }
                // Forwarding can change the local container before server confirmation.
                // Keep the original mode or the same click could also reach vanilla.
                return !compact;
            });
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!compactActive(s)) return true;
                if (event.key() == 256) return true;
                if (mc.options.keyInventory.matches(event)) s.onClose();
                // The hidden native grid must never receive drop/number/quick-craft hotkeys.
                return false;
            });
            ScreenEvents.remove(screen).register(s -> {
                if (s == current) {
                    current = null; window = -1; pending.clear(); selected.clear(); clearPlan();
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
                && eligible(container) && plan(container).ready();
    }

    /** Called only after an actual server slot update, never after a local prediction. */
    public static void serverSlotUpdated(int containerId, int slot) {
        if (current == null || window != containerId) return;
        cachedPlan = null;
        if (pending.remove(slot)) selected.add(slot);
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
            current = screen; window = screen.getMenu().containerId; selected.clear(); pending.clear();
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
        TerminalLogic.Plan plan = plan(screen);
        boolean compact = layout.equals("Compact") && plan.ready();
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
