package com.endsight.storage;

import com.endsight.hud.Toast;
import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import com.endsight.visual.RarityOutline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The storage view: every page as a card, your inventory under them, and the real menu's
 * slots moved into place, all on one panel. The server's Storage menu is this same view with
 * no page open, so going from one to the other changes the highlight and nothing else.
 */
public final class LiveStorageView {
    /** HEAD runs from the panel's top to the first card; BOTTOM closes the panel under the last thing in it. */
    private static final int HEAD = 46, BOTTOM = 10, BELOW = 8, FOOT = 18, PAD = 12, GAP = 10, INSET = 2, GRID = 17, EDGE = 6;
    /** Five cards is the width of his window at GUI 2. Past that the eye has too far to travel. */
    private static final int MAX_COLS = 5;
    /** The header needs this much across however few cards there are; INLINE_PANEL fits Vanilla view beside the search box. */
    private static final int MIN_PANEL = 380, INLINE_PANEL = 480, SEARCH_W = 168, SEARCH_H = 20;
    /** Stack sizes, so a wall of pages does not build the same few strings every frame. */
    private static final String[] COUNTS = new String[128];
    static { for (int i = 0; i < COUNTS.length; i++) COUNTS[i] = Integer.toString(i); }
    private static AbstractContainerScreen<?> owner;
    private static EditBox search;
    /** The page on the first card. Static so scrolling survives the menu reopening for each page. */
    private static int firstPage;
    /** How many page cards fit, to notice a resize. */
    private static int shownCapacity;
    private static final Map<Slot, Point> originals = new IdentityHashMap<>();
    private static int oldLeft, oldTop, oldWidth, oldHeight;

    private record Point(int x, int y) {}
    /**
     * @param below  the inventory has a row of its own under the pages, otherwise it takes the last card
     * @param arrows there are more pages than cards
     */
    record Layout(int cell, int cardWidth, int cardHeight, int cols, int rows, int left, int top, boolean below, boolean arrows,
                  int panelX, int panelY, int panelWidth, int panelHeight) {
        int capacity() { return cols * rows; }
        int pageCapacity() { return below ? capacity() : capacity() - 1; }
        int x(int index) { return left + index % cols * (cardWidth + GAP); }
        int y(int index) { return top + index / cols * (cardHeight + GAP); }
        int inventoryHeight() { return GRID + 4 * cell + 4 + 1; }
        int inventoryX() { return below ? panelX + (panelWidth - cardWidth) / 2 : x(capacity() - 1); }
        int inventoryY() { return below ? top + rows * (cardHeight + GAP) - GAP + BELOW : y(capacity() - 1); }
        int right() { return panelX + panelWidth; }
        private boolean inline() { return panelWidth >= INLINE_PANEL; }
        int searchX() { return right() - PAD - SEARCH_W; }
        int searchY() { return panelY + (inline() ? 14 : 20); }
        int vanillaRight() { return inline() ? searchX() - 12 : right() - PAD; }
        int vanillaY() { return panelY + (inline() ? 20 : 8); }
        int arrowY() { return panelY + panelHeight - BOTTOM - 10; }
    }

    /**
     * Everything sits on one panel centred in the window, with the pages over the inventory
     * the way every chest in the game is laid out. The grid used to centre on the window with
     * the inventory in whatever was left, which left a quarter of the screen empty and the
     * title and search far from what they belong to, and Fear found that awkward in both of
     * the positions it had.
     *
     * Rows and columns stop at what the pages need. A third row of nothing would push the
     * inventory away from the cards, and 1080p at GUI 2 has room for one.
     *
     * Null means the window is too small for this view at all. That answer does not depend on
     * {@code pages}, so active() can ask per slot without counting them.
     */
    static Layout layout(int width, int height, int contentRows, int pages) {
        int cell = width < 354 ? 16 : 18;
        int cardWidth = 9 * cell + 4;
        int cardHeight = GRID + Math.max(5, contentRows) * cell + 5;
        int inventory = GRID + 4 * cell + 5;
        int fitCols = Math.clamp((width - 2 * EDGE + GAP) / (cardWidth + GAP), 1, MAX_COLS);
        int room = height - 2 * EDGE - HEAD - BOTTOM + GAP;
        int fitGrid = Math.max(0, (room - FOOT) / (cardHeight + GAP));
        int fitBelow = Math.max(0, (room - BELOW - inventory) / (cardHeight + GAP));
        if (fitCols * fitGrid < 2) return null;
        // Under the pages wherever that leaves a real wall of them. Below that the inventory
        // takes the grid's last card, which costs a page but stays put, and the pages must not
        // shrink to make room for it.
        boolean below = fitBelow >= 2 || fitBelow == 1 && pages <= fitCols;
        int cards = below ? pages : pages + 1;
        int cols = Math.min(fitCols, Math.max(3, cards));
        int rows = Math.clamp((cards + cols - 1) / cols, 1, below ? fitBelow : fitGrid);
        boolean arrows = cards > cols * rows;
        int gridWidth = cols * cardWidth + (cols - 1) * GAP;
        int gridHeight = rows * (cardHeight + GAP) - GAP;
        int panelWidth = Math.min(width - 2 * EDGE, Math.max(gridWidth + 2 * PAD, MIN_PANEL));
        int panelHeight = HEAD + gridHeight + (below ? BELOW + inventory : arrows ? FOOT : 0) + BOTTOM;
        int panelY = Math.max(EDGE, (height - panelHeight) / 2);
        return new Layout(cell, cardWidth, cardHeight, cols, rows, (width - gridWidth) / 2, panelY + HEAD, below, arrows,
                (width - panelWidth) / 2, panelY, panelWidth, panelHeight);
    }

    static Layout layout(AbstractContainerScreen<?> screen) {
        return layout(screen.width, screen.height, liveRows(screen), pageTotal(screen));
    }

    static int playerCell(int inventoryIndex) {
        if (inventoryIndex >= 9 && inventoryIndex < 36) return inventoryIndex - 9;
        return inventoryIndex >= 0 && inventoryIndex < 9 ? 27 + inventoryIndex : -1;
    }

    static int pageCell(int containerOrdinal, int size) {
        return containerOrdinal >= 9 && containerOrdinal < size ? containerOrdinal - 9 : -1;
    }

    /** The first card once a window of {@code room} cards is kept inside {@code total} pages. */
    static int clamp(int first, int total, int room) {
        return Math.max(0, Math.min(first, Math.max(0, total - room)));
    }

    /**
     * The first card with {@code page} showing. A page already in view moves nothing, or
     * opening one would slide the whole grid under the cursor that just clicked it.
     */
    static int reveal(int first, int page, int room) {
        return page >= first && page < first + room ? first : page / room * room;
    }

    public static boolean active(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> container && StoragePreview.overviewEnabled()
                && !StorageOverview.vanillaView() && (StoragePreview.currentPage(container) >= 0 || StoragePreview.isRoot(container))
                && layout(screen.width, screen.height, liveRows(container), 1) != null;
    }

    static void init(AbstractContainerScreen<?> screen) {
        if (!active(screen)) return;
        Minecraft mc = Minecraft.getInstance();
        // Placed by drawChrome, because the panel it sits on follows the number of pages.
        search = new EditBox(mc.font, 0, 0, 158, 13, Component.literal("Search storage"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(Theme.text());
        search.setHint(Component.literal("Search items..."));
        search.setValue(StorageSearch.query());
        search.setResponder(StorageSearch::setQuery);
        StorageSearch.suspend();
    }

    /** Counted once per screen, because a menu's slots never change and active() asks per slot. */
    private static int pageSlots(AbstractContainerScreen<?> screen) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        if (screen != counted) {
            counted = screen;
            countedSlots = 0;
            for (Slot slot : screen.getMenu().slots) if (slot.container != player.getInventory()) countedSlots++;
        }
        return countedSlots;
    }
    private static AbstractContainerScreen<?> counted;
    private static int countedSlots;

    private static int liveRows(AbstractContainerScreen<?> screen) {
        return Math.max(1, (pageSlots(screen) - 9 + 8) / 9);
    }

    /** Whether this view has taken the screen over. active() is for deciding that, this is for per-slot checks. */
    public static boolean owns(AbstractContainerScreen<?> screen) { return owner == screen; }

    /** Only positions change. Menu ids, Slot objects and all server transaction indices stay native. */
    public static void prepare(AbstractContainerScreen<?> screen) {
        if (!active(screen)) { restore(screen); return; }
        Layout l = layout(screen);
        boolean arrived = owner != screen;
        if (arrived) {
            if (owner != null) restore(owner);
            owner = screen;
            oldLeft = screen.leftPos; oldTop = screen.topPos;
            oldWidth = screen.imageWidth; oldHeight = screen.imageHeight;
            for (Slot slot : screen.getMenu().slots) originals.put(slot, new Point(slot.x, slot.y));
            screen.hoveredSlot = null;
        }
        // Not every frame: that would undo scrolling away from the open page. A resize counts,
        // because a small window has room for one page and it had better be that one.
        if (arrived || l.pageCapacity() != shownCapacity) {
            shownCapacity = l.pageCapacity();
            int shown = openPage(screen);
            if (shown >= 0) firstPage = reveal(firstPage, shown, shownCapacity);
        }
        int card = liveCard(screen, l);
        screen.leftPos = 0; screen.topPos = 0;
        // The overview background is inside the menu. Clicking a cached page or gap
        // must never be interpreted as vanilla's drop-carried-stack outside click.
        screen.imageWidth = screen.width; screen.imageHeight = screen.height;
        var playerInventory = Minecraft.getInstance().player.getInventory();
        int size = pageSlots(screen), ordinal = 0;
        for (Slot slot : screen.getMenu().slots) {
            slot.x = -10_000; slot.y = -10_000;
            if (slot.container == playerInventory) {
                int index = playerCell(slot.getContainerSlot());
                if (index >= 0) {
                    slot.x = l.inventoryX() + INSET + 1 + index % 9 * l.cell();
                    int row = index / 9;
                    slot.y = l.inventoryY() + GRID + 1 + row * l.cell() + (row == 3 ? 4 : 0);
                }
                continue;
            }
            int cell = pageCell(ordinal++, size);
            if (cell < 0 || card < 0) continue;
            slot.x = l.x(card) + INSET + 1 + cell % 9 * l.cell();
            slot.y = l.y(card) + GRID + 1 + cell / 9 * l.cell();
        }
        StorageSearch.suspend();
    }

    public static void restore(AbstractContainerScreen<?> screen) {
        if (owner != screen) return;
        originals.forEach((slot, p) -> { slot.x = p.x(); slot.y = p.y(); });
        originals.clear();
        screen.leftPos = oldLeft; screen.topPos = oldTop;
        screen.imageWidth = oldWidth; screen.imageHeight = oldHeight;
        screen.hoveredSlot = null;
        owner = null;
    }

    /** Compact 16px cells cannot use vanilla's overlapping 18px padded hit areas. */
    public static boolean slotHovered(Slot slot, double x, double y) {
        return slot.x >= 0 && slot.y >= 0 && within(x, y, slot.x, slot.y, 16, 16);
    }

    /**
     * How many pages there are, ender chest included. Pages run in order with no gaps from
     * the ender chest as page 0, so a page's number is its place and the open one keeps
     * its spot among the rest. It used to be pulled out to the top left, which moved every
     * page behind it each time another was opened.
     */
    private static int pageTotal(AbstractContainerScreen<?> screen) {
        int count = StoragePreview.currentPageCount(screen);
        // The menu lists pages nothing has been saved from yet, for a new player above all.
        if (StoragePreview.isRoot(screen)) count = StorageOverview.pages(screen).getLast();
        for (PageSnapshot snapshot : StoragePreview.snapshots().values())
            count = Math.max(count, Math.max(snapshot.page(), snapshot.pageCount()));
        return Math.max(Math.min(54, count), StoragePreview.currentPage(screen)) + 1;
    }

    /**
     * The page to highlight. The Storage menu has none of its own, only the one a click is on
     * its way to: reaching any page goes through that menu, /storage and then a click, and it
     * used to show as itself for half a second on every change. Now the view just stays, and
     * the page's saved items stand in until the real slots land in the same place.
     */
    private static int openPage(AbstractContainerScreen<?> screen) {
        int page = StoragePreview.currentPage(screen);
        return page >= 0 ? page : StorageOverview.transitPage();
    }

    private static int clampFirst(int total, Layout l) {
        firstPage = clamp(firstPage, total, l.pageCapacity());
        return firstPage;
    }

    /** Which card the open page is on, or -1 while it is scrolled out of view. */
    private static int liveCard(AbstractContainerScreen<?> screen, Layout l) {
        int card = StoragePreview.currentPage(screen) - clampFirst(pageTotal(screen), l);
        return card >= 0 && card < l.pageCapacity() ? card : -1;
    }

    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my, float delta) {
        prepare(screen);
        int liveRows = liveRows(screen);
        Layout l = layout(screen);
        var mc = Minecraft.getInstance();
        g.nextStratum();
        int open = openPage(screen);
        drawChrome(screen, g, l, mx, my, delta);
        drawPages(screen, g, l, open, liveRows, mx, my);

        // These native passes preserve quick-craft previews, cursor stacks, tooltips
        // and snapback animations instead of simulating container interactions.
        g.nextStratum();
        screen.extractContents(g, mx, my, delta);
        if (!StorageSearch.query().isBlank()) {
            for (Slot slot : screen.getMenu().slots)
                if (slot.x >= 0 && (StorageSearch.includesInventory() || slot.container != mc.player.getInventory())
                        && StorageSearch.matches(slot.getItem(), StorageSearch.query()))
                    outline(g, slot.x - 1, slot.y - 1, l.cell() + 1, l.cell() + 1, Theme.obs());
        }
        // On the way to a page nothing can be on the cursor: changing pages is refused while
        // carrying, and the Storage menu only ever hands over the glass pane just clicked.
        if (!StorageOverview.inTransit()) screen.extractCarriedItem(g, mx, my);
        screen.extractSnapbackItem(g);
        screen.extractTooltip(g, mx, my);
    }

    private static void drawChrome(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Layout l, int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        Draw.rect(g, 0, 0, screen.width, screen.height,
                Draw.alpha(Theme.bg(), (float) (StorageOverview.opacity() / 100)));
        // A ring and a fill, as on the mod menu: see-through, a solid border would show through.
        // Surface rather than the menu's ground, which is the colour of the scrim behind it here.
        Draw.roundedRect(g, l.panelX(), l.panelY(), l.panelWidth(), l.panelHeight(), Theme.RADIUS_LG, Draw.alpha(Theme.surface(), .55f));
        Draw.roundedRing(g, l.panelX() - 1, l.panelY() - 1, l.panelWidth() + 2, l.panelHeight() + 2, Theme.RADIUS_LG + 1, Theme.line());
        int textX = l.panelX() + PAD + INSET;
        Draw.text(g, font, "Storage", textX, l.panelY() + 14, Theme.text());
        // The same words with a page open: a click changes the highlight and nothing else, and
        // the longer line it used to swap to was cut off mid-word on smaller windows.
        Draw.text(g, font, "Click a page to open it", textX, l.panelY() + 28, Theme.muted());
        Draw.roundedOutline(g, l.searchX(), l.searchY(), SEARCH_W, SEARCH_H, 4, Theme.line(), Theme.input());
        if (search != null) {
            search.setX(l.searchX() + 5);
            search.setY(l.searchY() + 6);
            search.extractWidgetRenderState(g, mx, my, delta);
        }
        Draw.textRight(g, font, "Vanilla view", l.vanillaRight(), l.vanillaY(),
                within(mx, my, l.vanillaRight() - 84, l.vanillaY() - 4, 84, 16) ? Theme.accent() : Theme.muted());
        if (l.arrows()) {
            Draw.text(g, font, "<", l.panelX() + PAD, l.arrowY(), Theme.text());
            Draw.textRight(g, font, ">", l.right() - PAD, l.arrowY(), Theme.text());
        }
    }

    /**
     * Every card, each in a stratum of its own.
     *
     * That one call is most of this view's speed. Each thing drawn is checked for overlap
     * against everything already in its stratum, so one stratum holding the whole wall cost
     * the square of it: 87% of a 28ms frame at 31 fps, measured with eight full cards at his
     * real window. Cards never overlap each other, so nothing is lost by splitting them.
     */
    private static void drawPages(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Layout l, int open,
                                  int liveRows, int mx, int my) {
        var font = Minecraft.getInstance().font;
        String query = StorageSearch.query();
        boolean searching = !query.isBlank(), carrying = !screen.getMenu().getCarried().isEmpty();
        // A highlighted page in the Storage menu has no slots here yet: its saved items stand in.
        boolean slots = StoragePreview.currentPage(screen) >= 0;
        g.nextStratum();
        drawInventory(g, font, l);

        int total = Math.max(pageTotal(screen), open + 1), first = clampFirst(total, l);
        for (int index = 0; index < l.pageCapacity() && first + index < total; index++) {
            int other = first + index;
            String title = other == 0 ? "Ender Chest" : "PV " + other;
            g.nextStratum();
            PageSnapshot snapshot = StoragePreview.snapshots().get(other);
            // Worked out once per search rather than per stack per frame.
            BitSet found = StoragePreview.searchMatches(snapshot, query);
            int matches = found.cardinality();
            boolean highlighted = other == open;
            int colour = highlighted ? Theme.accent() : matches > 0 ? Theme.obs()
                    : within(mx, my, l.x(index), l.y(index), l.cardWidth(), l.cardHeight()) ? Theme.accent() : Theme.text();
            if (highlighted && slots) {
                drawFrame(g, title, l, index, liveRows, true, colour);
                drawStatus(g, font, l, index, other, matches);
                continue;
            }
            // By the card's height and not the open page's rows: opening the three-row ender
            // chest was cutting every other page down to three rows.
            int rows = snapshot == null ? 5 : Math.max(1, Math.min(Math.max(5, liveRows), snapshot.rows() - 1));
            drawFrame(g, title, l, index, rows, highlighted, colour);
            drawStatus(g, font, l, index, other, matches);
            if (snapshot == null) {
                Draw.text(g, font, "Open once to preview", l.x(index) + 5, l.y(index) + 30, Theme.muted());
                continue;
            }
            for (int cell = 0; cell < rows * 9; cell++) {
                ItemStack stack = snapshot.at(cell + 9);
                if (stack.isEmpty()) continue;
                int x = l.x(index) + INSET + 1 + cell % 9 * l.cell();
                int y = l.y(index) + GRID + 1 + cell / 9 * l.cell();
                // Rows do not overlap either, so the same trick again inside a card.
                if (cell % 9 == 0) g.nextStratum();
                item(g, font, stack, x, y);
                if (found.get(cell + 9)) outline(g, x - 1, y - 1, l.cell(), l.cell(), Theme.obs());
                // Keeps the page's layout while making its hits the first thing the eye finds.
                else if (searching) Draw.rect(g, x, y, 16, 16, Draw.alpha(Theme.bg(), .72f));
                if (!carrying && within(mx, my, x, y, 16, 16)) g.setTooltipForNextFrame(font, stack, mx, my);
            }
        }
    }

    /** The corner of a card: how many a search found on it, or why it will not open. */
    private static void drawStatus(GuiGraphicsExtractor g, Font font, Layout l, int index, int page, int matches) {
        int right = l.x(index) + l.cardWidth() - INSET, y = l.y(index);
        if (matches > 0) {
            String label = matches + " found";
            int width = font.width(label) + 8;
            Draw.roundedRect(g, right - width, y, width, 13, 3, Theme.obs());
            Draw.textRight(g, font, label, right - 4, y + 3, Theme.bg());
            return;
        }
        String access = StorageOverview.accessLabel(page);
        if (!access.isEmpty()) Draw.textRight(g, font, access, right, y + 3, Theme.muted());
    }

    /**
     * One stack not in a live slot: its rarity, its icon and its count.
     *
     * The count is drawn here because vanilla's decoration runs a fresh bidi pass for every
     * number, every frame, and a wall of pages is hundreds of them. It lands on the same
     * pixels. Only a damaged item needs the real thing, for its bar.
     */
    static void item(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        RarityOutline.behindHotbar(g, x, y, stack);
        g.item(stack, x, y);
        if (stack.isBarVisible()) g.itemDecorations(font, stack, x, y);
        else if (stack.getCount() > 1) {
            int count = stack.getCount();
            Draw.textRight(g, font, count < COUNTS.length ? COUNTS[count] : Integer.toString(count), x + 17, y + 9, -1);
        }
    }

    private static void drawInventory(GuiGraphicsExtractor g, Font font, Layout l) {
        int x = l.inventoryX() + INSET, y = l.inventoryY();
        Draw.text(g, font, "Your inventory", x, y + 3, Theme.text());
        grid(g, x, y + GRID, 3, l.cell());
        grid(g, x, y + GRID + 3 * l.cell() + 4, 1, l.cell());
    }

    private static void drawFrame(GuiGraphicsExtractor g, String title, Layout l, int index, int rows, boolean live, int colour) {
        int x = l.x(index), y = l.y(index);
        var font = Minecraft.getInstance().font;
        if (live) {
            Draw.rect(g, x - 2, y - 2, l.cardWidth() + 4, l.cardHeight() + 4, Draw.alpha(Theme.accent(), .07f));
            outline(g, x - 2, y - 2, l.cardWidth() + 4, l.cardHeight() + 4, Theme.accent());
        }
        Draw.text(g, font, title, x + INSET, y + 3, colour);
        grid(g, x + INSET, y + GRID, rows, l.cell());
    }

    /**
     * Lines across the whole grid, not a box per cell: that was 180 rectangles a card, the
     * largest part of what the frame had to check for overlap. Boxes shared their edges, so
     * inner lines were drawn twice and read brighter than the rim; .31 is that doubled .17.
     */
    private static void grid(GuiGraphicsExtractor g, int x, int y, int rows, int cell) {
        int w = 9 * cell + 1, h = rows * cell + 1;
        outline(g, x, y, w, h, Draw.alpha(Theme.text(), .17f));
        int inner = Draw.alpha(Theme.text(), .31f);
        for (int col = 1; col < 9; col++) Draw.rect(g, x + col * cell, y + 1, 1, h - 2, inner);
        for (int row = 1; row < rows; row++) Draw.rect(g, x + 1, y + row * cell, w - 2, 1, inner);
    }

    private static boolean nativeArea(AbstractContainerScreen<?> screen, Layout l, double x, double y) {
        int card = liveCard(screen, l);
        return card >= 0 && within(x, y, l.x(card) + INSET, l.y(card) + GRID, 9 * l.cell(), liveRows(screen) * l.cell())
                || within(x, y, l.inventoryX() + INSET, l.inventoryY() + GRID, 9 * l.cell(), 4 * l.cell() + 4);
    }

    static boolean click(AbstractContainerScreen<?> screen, double x, double y, int button, boolean shift) {
        prepare(screen);
        Layout l = layout(screen);
        if (nativeArea(screen, l, x, y)) {
            if (search != null) search.setFocused(false);
            return false;
        }
        if (button != 0) return true;
        if (search != null) search.setFocused(within(x, y, l.searchX(), l.searchY(), SEARCH_W, SEARCH_H));
        if (within(x, y, l.vanillaRight() - 84, l.vanillaY() - 4, 84, 16)) {
            if (canNavigate(screen)) StorageOverview.showVanilla(screen);
            return true;
        }
        if (l.arrows() && within(x, y, l.panelX() + PAD - 6, l.arrowY() - 5, 25, 18)) { move(screen, -1); return true; }
        if (l.arrows() && within(x, y, l.right() - PAD - 19, l.arrowY() - 5, 25, 18)) { move(screen, 1); return true; }
        int open = openPage(screen), total = pageTotal(screen), first = clampFirst(total, l);
        for (int i = 0; i < l.pageCapacity() && first + i < total; i++)
            if (first + i != open && within(x, y, l.x(i), l.y(i), l.cardWidth(), l.cardHeight())) {
                PageSnapshot snapshot = StoragePreview.snapshots().get(first + i);
                int cell = snapshot == null ? -1 : cellAt(l, i, Math.min(Math.max(5, liveRows(screen)), snapshot.rows() - 1), x, y);
                boolean free = screen.getMenu().getCarried().isEmpty();
                // The click was on a stack, not on a page: it should land there, as if the page had
                // been open already. Fear clicked items on closed pages and had to click them twice.
                StorageOverview.clickOnArrival(first + i, cell + 9, cell < 0 || !free ? ItemStack.EMPTY : snapshot.at(cell + 9), shift);
                navigate(screen, first + i);
                return true;
            }
        return true;
    }

    /** The cell of card {@code index} under the mouse, or -1 off its grid. */
    static int cellAt(Layout l, int index, int rows, double x, double y) {
        int col = (int) Math.floor((x - (l.x(index) + INSET + 1)) / l.cell());
        int row = (int) Math.floor((y - (l.y(index) + GRID + 1)) / l.cell());
        return col >= 0 && col < 9 && row >= 0 && row < rows ? row * 9 + col : -1;
    }

    private static boolean canNavigate(AbstractContainerScreen<?> screen) {
        if (screen.getMenu().getCarried().isEmpty()) return true;
        Toast.warn("Storage", "Place the cursor stack before changing pages");
        return false;
    }

    private static void navigate(AbstractContainerScreen<?> screen, int page) {
        if (!canNavigate(screen)) return;
        if (page == 0) {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) connection.sendCommand("ec");
        } else StorageOverview.openPage(screen, page);
    }

    static void move(AbstractContainerScreen<?> screen, int direction) {
        if (!canNavigate(screen)) return;
        Layout l = layout(screen);
        int total = pageTotal(screen);
        if (l.pageCapacity() == 1) {
            int next = StoragePreview.currentPage(screen) + direction;
            if (next >= 0 && next < total) navigate(screen, next);
        } else {
            firstPage += direction * l.pageCapacity();
            clampFirst(total, l);
        }
    }

    static boolean key(AbstractContainerScreen<?> screen, KeyEvent event) {
        if (search != null && search.isFocused() && event.key() != GLFW.GLFW_KEY_ESCAPE) {
            search.keyPressed(event); return true;
        }
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN) { move(screen, 1); return true; }
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP) { move(screen, -1); return true; }
        return false;
    }

    static boolean character(CharacterEvent event) {
        if (search == null || !search.isFocused()) return false;
        search.charTyped(event); return true;
    }

    private static boolean within(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int width, int height, int colour) {
        Draw.rect(g, x, y, width, 1, colour);
        Draw.rect(g, x, y + height - 1, width, 1, colour);
        Draw.rect(g, x, y, 1, height, colour);
        Draw.rect(g, x + width - 1, y, 1, height, colour);
    }
}
