package com.endsight.storage;

import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers what was in each storage page and draws it again when you hover that page
 * in the Storage menu.
 *
 * The server drives all of this through window titles, which is what makes it cheap:
 * the root menu is titled "Storage" and holds items named "Storage Page N", and
 * opening one of those opens a window titled "Storage Page (N/7)". The page number is
 * on both ends already, so nothing has to be matched by item identity - no NBT tag to
 * hunt for, no slot index to hard-code, and a page moving in the root menu changes
 * nothing. If the server ever renames its windows this breaks loudly and in one place,
 * which is the trade being made.
 *
 * Capture is on tick, not only on close. Closing is how the feature was described -
 * snapshot the page as it closes - but "closed" is not a moment this side of the
 * connection can trust: paging replaces the whole screen, and reading a menu Minecraft
 * is midway through tearing down is how you get an empty snapshot over a page that had
 * things in it. Ticking keeps a copy that is at most one tick stale, so the last one
 * taken is the closing state whether or not the close is clean.
 */
public final class StoragePreview {

    private StoragePreview() {
    }

    // The two shapes the server uses, both anchored: a page named in passing inside
    // some other menu's text must not be mistaken for the page item itself.
    private static final Pattern PAGE_WINDOW = Pattern.compile("^Storage Page \\((\\d+)\\s*/\\s*(\\d+)\\)$");
    private static final Pattern PAGE_ITEM = Pattern.compile("^Storage Page (\\d+)$");

    private static final Map<Integer, PageSnapshot> SNAPSHOTS = new HashMap<>();

    /**
     * Rows of the page skipped when drawing, counted from the top.
     *
     * The server puts its own furniture in the first row - the close barrier and the
     * page arrows - and that is chrome, not stash. Dropped at draw time rather than at
     * capture so the snapshot stays a faithful copy of the page; if the server ever
     * moves its furniture, this is one number rather than a re-capture.
     */
    private static final int SKIP_ROWS = 1;

    // ── module state, owned here so the card and the feature cannot disagree ──
    private static boolean enabled = true;
    private static boolean capturing = true;
    private static double delayMs = 200;
    private static boolean showCounts = true;

    /** Nudge from the default anchor, set on the placement screen. Resets on restart. */
    static int offsetX = 0;
    static int offsetY = 0;

    /** Which page the cursor is resting on and since when, for the hover delay. */
    private static int hoverPage = -1;
    private static long hoverSince = 0L;

    /** Most recent page seen, so placement has something real to show. */
    private static int lastPage = -1;

    public static Module module() {
        return new Module("storage.preview", "Storage Preview",
                "Hover a storage page to see inside. Item search.", "Storage",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Section("Preview"),
                        new Setting.Toggle("Snapshot on close",
                                "Remember each page as you leave it.",
                                () -> capturing, v -> capturing = v),
                        new Setting.Slider("Preview delay",
                                "Hover time before it opens.",
                                0, 1000, 50, () -> delayMs, v -> delayMs = v, "ms"),
                        new Setting.Toggle("Show stack sizes",
                                "Draw the count on each stack.",
                                () -> showCounts, v -> showCounts = v),
                        new Setting.Action("Reposition preview",
                                "Drag the preview where you want it.",
                                "Move", StoragePreview::openPlacement)),
                        StorageSearch.settings()));
    }

    private static List<Setting> concat(List<Setting> a, List<Setting> b) {
        List<Setting> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void openPlacement() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new PreviewPlacementScreen(mc.screen));
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;

            // Registered per screen rather than globally because that is the only shape
            // the screen API offers for these - they hang off a specific screen.
            ScreenEvents.afterTick(screen).register(s -> capture(container));
            ScreenEvents.remove(screen).register(s -> capture(container));
            ScreenEvents.afterExtract(screen).register(
                    (s, g, mouseX, mouseY, delta) -> draw(container, g));
        });
    }

    // ── capture ───────────────────────────────────────────────────────────────

    private static void capture(AbstractContainerScreen<?> screen) {
        if (!enabled || !capturing) return;

        Matcher m = PAGE_WINDOW.matcher(plain(screen.getTitle()));
        if (!m.matches()) return;

        int page = parse(m.group(1));
        int count = parse(m.group(2));
        if (page < 0) return;

        List<ItemStack> items = containerSlots(screen);
        if (items.isEmpty()) return;

        // A page that has not arrived yet reads as all-empty for a frame or two after
        // the window opens. Letting that overwrite a good snapshot would blank the
        // preview every time you so much as glance at a page, so an empty read never
        // replaces a filled one. Emptying a page for real costs you one stale preview,
        // which is the cheaper of the two mistakes.
        PageSnapshot existing = SNAPSHOTS.get(page);
        if (existing != null && existing.filled() > 0 && allEmpty(items)) return;

        SNAPSHOTS.put(page, new PageSnapshot(page, count, items, System.currentTimeMillis()));
        lastPage = page;
        SnapshotStore.markDirty();
    }

    private static boolean allEmpty(List<ItemStack> items) {
        for (ItemStack s : items) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    /**
     * The window's own slots, without the player inventory underneath.
     *
     * Split by which Container a slot belongs to rather than by index. Index maths
     * ("the first 54 are the chest") holds for a plain chest and stops holding the
     * moment the server opens anything else, and this menu is whatever the server says
     * it is.
     */
    private static List<ItemStack> containerSlots(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();

        List<ItemStack> out = new ArrayList<>();
        try {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container == playerInv) continue;
                out.add(slot.getItem().copy());
            }
        } catch (RuntimeException ignored) {
            // Reached from remove(), where the menu may already be half torn down. A
            // missed snapshot is a stale preview; throwing here would take the screen
            // down with it.
            return List.of();
        }
        return out;
    }

    // ── geometry ──────────────────────────────────────────────────────────────

    static final int CELL = 18;
    static final int PAD = 8;
    static final int HEADER = 16;

    /**
     * Air between the panel and the game's own window.
     *
     * The first pass sat the panel flush on the window, covering the row of arrows and
     * the close button on the grounds that nobody reads them. It was correct and it
     * looked wrong - two panels touching read as one badly-drawn panel, and the seam is
     * the first thing the eye finds. A gap costs nothing and stops it looking pasted on.
     */
    static final int GAP = 6;

    /**
     * Where the game's storage window last was, remembered so the placement screen has
     * a real frame to aim at instead of a guess.
     *
     * Absolute screen coordinates rather than an offset from centre, because that is
     * what the panel is positioned against and the two must not drift apart. They only
     * hold while the GUI scale is unchanged, which is the case for anything short of
     * changing video settings mid-placement.
     */
    static int[] frameCells;      // x,y pairs for the container's own slots
    static int frameGridLeft = -1;
    static int frameWindowTop = -1;
    static int frameRows = 6;

    /**
     * Panel rectangle for a given anchor, recomputed every time it is needed.
     *
     * Not cached between frames even though drawing and dragging both want it, because
     * the window moves when the game window is resized and a stale rectangle would then
     * be catching drags where nothing is drawn.
     */
    static int[] boundsAt(int gridLeft, int windowTop, int rows, int screenW, int screenH) {
        int w = PageSnapshot.COLS * CELL + PAD * 2;
        int h = PAD + HEADER + rows * CELL + PAD;

        // Columns aligned to the window's columns, floating a gap clear of the top of it.
        int x = gridLeft - PAD + offsetX;
        int y = windowTop - GAP - h + offsetY;

        x = clamp(x, 4, Math.max(4, screenW - w - 4));
        y = clamp(y, 4, Math.max(4, screenH - h - 4));
        return new int[]{x, y, w, h};
    }

    /** Records where this window is, and returns the panel rectangle for it. */
    private static int[] bounds(AbstractContainerScreen<?> screen, int rows) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();

        List<int[]> cells = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInv) continue;
            minX = Math.min(minX, slot.x);
            cells.add(new int[]{screen.leftPos + slot.x, screen.topPos + slot.y});
        }
        if (minX == Integer.MAX_VALUE) return null;

        frameGridLeft = screen.leftPos + minX;
        frameWindowTop = screen.topPos;
        frameRows = Math.max(1, cells.size() / PageSnapshot.COLS);
        frameCells = new int[cells.size() * 2];
        for (int i = 0; i < cells.size(); i++) {
            frameCells[i * 2] = cells.get(i)[0];
            frameCells[i * 2 + 1] = cells.get(i)[1];
        }

        return boundsAt(frameGridLeft, frameWindowTop, rows, screen.width, screen.height);
    }

    /** Rows actually drawn: the page minus the server's furniture row. */
    static int drawnRows(PageSnapshot snap) {
        return snap == null ? Math.max(1, frameRows - SKIP_ROWS) : Math.max(1, snap.rows() - SKIP_ROWS);
    }

    /** The live map, for SnapshotStore to read on save and fill on load. */
    public static Map<Integer, PageSnapshot> snapshots() {
        return SNAPSHOTS;
    }

    /** The most recent page seen, for the placement screen to show something real. */
    static PageSnapshot lastSnapshot() {
        return SNAPSHOTS.get(lastPage);
    }

    // ── draw ──────────────────────────────────────────────────────────────────

    private static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!enabled) {
            hoverPage = -1;
            return;
        }

        // hoveredSlot rather than a hit test of our own: it is already correct about
        // inactive and scrolled-away slots, and it is set during the same extract pass
        // this runs at the end of.
        Slot slot = screen.hoveredSlot;
        int page = slot == null ? -1 : pageOf(slot.getItem());
        if (page != hoverPage) {
            hoverPage = page;
            hoverSince = System.currentTimeMillis();
        }

        PageSnapshot snap = SNAPSHOTS.get(page);

        // The frame is recorded on every container screen, hovered or not, so opening
        // Storage once is enough to teach the placement screen where the window sits.
        int[] b = bounds(screen, drawnRows(snap));
        if (page < 0 || b == null) return;
        if (page >= 0) lastPage = page;

        long now = System.currentTimeMillis();
        if (now - hoverSince < (long) delayMs) return;

        // Above what is already drawn, so the panel is not buried under the slot grid
        // it is sitting on top of.
        g.nextStratum();
        drawPanel(g, snap, b, now, false);
    }

    /**
     * The panel itself. Shared with the placement screen so what you place is exactly
     * what you later see - a separate "preview of the preview" would drift.
     */
    static void drawPanel(GuiGraphicsExtractor g, PageSnapshot snap, int[] b, long now, boolean placing) {
        Font font = Minecraft.getInstance().font;
        int x = b[0], y = b[1], w = b[2], h = b[3];
        int rows = drawnRows(snap);

        // Themed rather than grey. A plain hairline border is the correct colour and
        // reads as a debug box - the palette is the only thing tying this panel to the
        // rest of the mod, since it is drawn over the game rather than inside a screen
        // anyone would recognise. At rest the border sits halfway to accent so it is
        // tinted without shouting; while placing it goes the whole way and gains a ring.
        int border = placing ? Theme.accent() : Draw.lerp(Theme.line(), Theme.accent(), 0.55f);
        if (placing) {
            Draw.roundedRect(g, x - 2, y - 2, w + 4, h + 4, Theme.RADIUS + 2,
                    Draw.alpha(Theme.accent(), 0.22f));
        }
        Draw.roundedOutline(g, x, y, w, h, Theme.RADIUS, border, Theme.surface());

        String title;
        String right;
        if (snap == null) {
            title = "Storage Preview";
            right = placing ? "drag to place" : "no snapshot";
        } else {
            title = "Storage Page " + snap.page()
                    + (snap.pageCount() > 0 ? " / " + snap.pageCount() : "");
            right = placing ? "drag to place" : snap.age(now);
        }
        Draw.text(g, font, title, x + PAD, y + PAD, placing ? Theme.accent() : Theme.text());
        Draw.textRight(g, font, right, x + w - PAD, y + PAD,
                placing ? Theme.accent() : Theme.dim());

        // Splits header from grid. Without it the title floats over the cells and the
        // panel reads as one undifferentiated box.
        Draw.rect(g, x + PAD, y + PAD + 11, w - PAD * 2, 1, Draw.alpha(Theme.accent(), 0.35f));

        int gx = x + PAD;
        int gy = y + PAD + HEADER;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < PageSnapshot.COLS; c++) {
                int cx = gx + c * CELL;
                int cy = gy + r * CELL;
                Draw.roundedRect(g, cx, cy, CELL - 2, CELL - 2, 2, Theme.input());

                if (snap == null) continue;
                ItemStack stack = snap.at((r + SKIP_ROWS) * PageSnapshot.COLS + c);
                if (stack.isEmpty()) continue;
                g.item(stack, cx, cy);
                if (showCounts) g.itemDecorations(font, stack, cx, cy);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────


    // ── shared with StorageSearch ─────────────────────────────────────────────

    /** True for the root Storage menu and for any single page of it. */
    static boolean isStorageWindow(AbstractContainerScreen<?> screen) {
        String t = plain(screen.getTitle());
        return t.equals("Storage") || PAGE_WINDOW.matcher(t).matches();
    }

    static int pageOfItem(ItemStack stack) {
        return pageOf(stack);
    }

    static boolean hasSnapshot(int page) {
        return SNAPSHOTS.containsKey(page);
    }

    /** How many stacks in a remembered page match, furniture row excluded. */
    static int matchesIn(int page, String query) {
        PageSnapshot snap = SNAPSHOTS.get(page);
        if (snap == null) return 0;
        int n = 0;
        for (int i = SKIP_ROWS * PageSnapshot.COLS; i < snap.items().size(); i++) {
            if (StorageSearch.matches(snap.at(i), query)) n++;
        }
        return n;
    }

    static String plainText(Component c) {
        return plain(c);
    }

    private static int pageOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        Matcher m = PAGE_ITEM.matcher(plain(stack.getHoverName()));
        return m.matches() ? parse(m.group(1)) : -1;
    }

    /**
     * Component text with formatting removed.
     *
     * getString() drops styles that arrived as style objects, but a server is free to
     * put legacy section codes in the literal text and this one does colour its titles,
     * so both have to go or every match fails on an invisible prefix.
     */
    private static String plain(Component c) {
        if (c == null) return "";
        return c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }
}
