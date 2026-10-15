package com.endsight.storage;

import com.endsight.ui.Draw;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.hud.Toast;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** A full-page index over the server's Storage menu, backed by visited page snapshots. */
public final class StorageOverview {

    private StorageOverview() {
    }

    static final int CARD_W = 166;
    static final int CARD_H = 112;
    static final int GAP = 10;
    private static final int CELL = 18;
    private static final int GRID_LEFT = 2, GRID_TOP = 17;
    private static final int TOP = 52;
    private static final int BOTTOM = 28;

    private static Screen rootScreen;
    private static boolean vanilla;
    private static EditBox search;
    private static int scrollRow;
    private static double backgroundOpacity = 90;
    private static AbstractContainerScreen<?> pagesScreen;
    private static List<Integer> pageCache = List.of();
    private static Map<Integer, MenuPage> menuPages = Map.of();
    private static PageRequest pendingPage;

    public static Setting opacitySetting() {
        return new Setting.Slider("Overview opacity", "Darkness behind the all-page storage view; zero is clear.",
                0, 100, 5, () -> backgroundOpacity, value -> backgroundOpacity = value, "%");
    }

    public static boolean active(Screen screen) {
        return screen == rootScreen && !vanilla && StoragePreview.overviewEnabled();
    }

    static boolean backButton(AbstractContainerScreen<?> screen, double mx, double my) {
        return StoragePreview.overviewEnabled() && isPage(screen)
                && within(mx, my, screen.width - 91, 8, 79, 18);
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageOverview::tickPageRequest);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            boolean root = isRoot(container);
            if (!root && !isPage(container)) return;

            if (root) {
                pagesScreen = null;
                rootScreen = screen;
                vanilla = false;
                scrollRow = 0;
                StorageSearch.suspend();
                search = new EditBox(client.font, w - 174, 25, 158, 13,
                        Component.literal("Search storage"));
                search.setBordered(false);
                search.setMaxLength(48);
                search.setTextColor(Theme.text());
                search.setHint(Component.literal("Search items..."));
                search.setValue(StorageSearch.query());
                search.setResponder(StorageSearch::setQuery);
                // Server menu updates arrive on ticks. Page discovery parses every
                // slot name and sorts it; rendering and hit testing share that work.
                ScreenEvents.afterTick(screen).register(s -> pagesScreen = null);
            }

            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                if (isRoot(container)) drawRoot(container, g, mx, my);
                else drawBack(container, g, mx, my);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
                    !click(container, click.x(), click.y(), click.button()));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, hx, vy) ->
                    !scroll(container, vy));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!active(s)) return true;
                if (event.key() == GLFW.GLFW_KEY_ESCAPE) return true;
                if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
                    moveRows(container, 1);
                    return false;
                }
                if (event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                    moveRows(container, -1);
                    return false;
                }
                if (search != null && search.isFocused()) {
                    search.keyPressed(event);
                    return false;
                }
                if (client.options.keyInventory.matches(event)) screen.onClose();
                // The hidden server grid can retain its last hovered slot after a
                // view switch. Number/drop hotkeys must never act on that stale slot.
                return false;
            });
            ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> {
                if (!active(s) || search == null || !search.isFocused()) return true;
                search.charTyped(event);
                return false;
            });
            ScreenEvents.remove(screen).register(s -> {
                if (rootScreen == s) {
                    rootScreen = null;
                    search = null;
                    pagesScreen = null;
                    pageCache = List.of();
                    menuPages = Map.of();
                }
            });
        });
    }

    private static boolean isRoot(AbstractContainerScreen<?> screen) {
        return StoragePreview.plainText(screen.getTitle()).equals("Storage");
    }

    /** A storage page, or the ender chest - both get the way back to the overview. */
    private static boolean isPage(AbstractContainerScreen<?> screen) {
        return (StoragePreview.isStorageWindow(screen) && !isRoot(screen)) || StoragePreview.isEnderChest(screen);
    }

    /** Every page, the ender chest first: it is the one storage everybody has. */
    private static List<Integer> pages(AbstractContainerScreen<?> screen) {
        if (pagesScreen == screen) return pageCache;
        Set<Integer> found = new TreeSet<>();
        found.add(StoragePreview.ENDER_CHEST);
        Container playerInv = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();
        Map<Integer, MenuPage> targets = new HashMap<>();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (slot.container == playerInv) continue;
            MenuPage page = menuPage(slot.getItem(), i);
            if (page == null) continue;
            found.add(page.page());
            mergePage(targets, page);
        }
        int count = 0;
        for (PageSnapshot snap : StoragePreview.snapshots().values()) {
            count = Math.max(count, Math.max(snap.page(), snap.pageCount()));
        }
        count = Math.min(count, 54);
        for (int p = 1; p <= count; p++) found.add(p);
        pagesScreen = screen;
        menuPages = Map.copyOf(targets);
        return pageCache = List.copyOf(found);
    }

    enum PageAccess { OPEN, LOCKED, UNKNOWN }
    record MenuPage(int page, int menuSlot, PageAccess access) { }
    private static final Pattern BLOCKED_PAGE = Pattern.compile(
            "\\b(?:lock|locked|unlock|buy|purchase|cost|price|requires?)\\b|community shop");
    private static final Pattern VIEW_PAGE = Pattern.compile("^click to (?:view|open)\\b.*$");

    /** Only the live menu can grant access; a remembered page is never permission. */
    static MenuPage menuPage(String name, String itemId, List<String> lore, int menuSlot) {
        int page = StoragePreview.pageNumber(name);
        if (page <= 0 || page > 54 || menuSlot < 0) return null;
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        boolean locked = id.equals("minecraft:red_stained_glass_pane") || id.equals("minecraft:barrier");
        boolean view = false;
        for (String line : lore) {
            String plain = StoragePreview.plainText(line).toLowerCase(Locale.ROOT);
            if (BLOCKED_PAGE.matcher(plain).find()) locked = true;
            if (VIEW_PAGE.matcher(plain).matches()) view = true;
        }
        return new MenuPage(page, menuSlot, locked ? PageAccess.LOCKED : view ? PageAccess.OPEN : PageAccess.UNKNOWN);
    }

    private static MenuPage menuPage(ItemStack stack, int menuSlot) {
        if (stack.isEmpty()) return null;
        String name = StoragePreview.plainText(stack.getHoverName());
        // The root also contains a community-shop purchase button named Locked Page.
        // Never derive its page from the slot's position or from an old snapshot.
        if (StoragePreview.pageNumber(name) <= 0) return null;
        var lore = stack.get(DataComponents.LORE);
        List<String> lines = lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList();
        return menuPage(name, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), lines, menuSlot);
    }

    private static void mergePage(Map<Integer, MenuPage> pages, MenuPage page) {
        MenuPage old = pages.putIfAbsent(page.page(), page);
        if (old != null) pages.put(page.page(), new MenuPage(page.page(), -1, PageAccess.UNKNOWN));
    }

    /** Re-read before clicking; metadata used to draw the card may be one tick old. */
    private static MenuPage livePage(AbstractContainerScreen<?> screen, int page) {
        Container playerInv = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();
        MenuPage found = null;
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (slot.container == playerInv) continue;
            MenuPage candidate = menuPage(slot.getItem(), i);
            if (candidate == null || candidate.page() != page) continue;
            if (found != null) return new MenuPage(page, -1, PageAccess.UNKNOWN);
            found = candidate;
        }
        return found;
    }

    enum OpenAction { WAIT, CLICK, LOCKED, CANCEL, EXPIRED }

    /** One user request, bound to its source screen/world and the arriving root menu. */
    static final class PageRequest {
        final int page;
        private final Object world, source;
        private final long started;
        private Object root;

        PageRequest(int page, Object world, Object source, long started) {
            this.page = page; this.world = world; this.source = source; this.started = started;
        }

        OpenAction advance(Object world, Object screen, boolean isRoot, MenuPage target, long now) {
            if (world == null || world != this.world || screen == null) return OpenAction.CANCEL;
            if (now < started || now - started >= 5000) return OpenAction.EXPIRED;
            if (!isRoot) return root == null && screen == source ? OpenAction.WAIT : OpenAction.CANCEL;
            if (root != null && root != screen) return OpenAction.CANCEL;
            root = screen;
            if (target == null || target.page() != page || target.menuSlot() < 0) return OpenAction.WAIT;
            if (target.access() == PageAccess.LOCKED) return OpenAction.LOCKED;
            return target.access() == PageAccess.OPEN ? OpenAction.CLICK : OpenAction.WAIT;
        }
    }

    static void openPage(AbstractContainerScreen<?> source, int page) {
        Minecraft mc = Minecraft.getInstance();
        if (page <= 0 || page > 54 || mc.screen != source || mc.player == null || mc.level == null
                || mc.getConnection() == null || mc.gameMode == null) return;
        pendingPage = new PageRequest(page, mc.level, source, System.currentTimeMillis());
        if (isRoot(source)) tickPageRequest(mc);
        else mc.getConnection().sendCommand("storage");
    }

    private static void tickPageRequest(Minecraft mc) {
        PageRequest request = pendingPage;
        if (request == null) return;
        if (mc.player == null || mc.gameMode == null) { pendingPage = null; return; }
        AbstractContainerScreen<?> screen = mc.screen instanceof AbstractContainerScreen<?> container ? container : null;
        boolean root = screen != null && isRoot(screen);
        MenuPage target = root ? livePage(screen, request.page) : null;
        OpenAction action = request.advance(mc.level, mc.screen, root, target, System.currentTimeMillis());
        if (action == OpenAction.WAIT) return;
        // Consume before sending the click: local prediction can replace the screen.
        pendingPage = null;
        if (action == OpenAction.CLICK && mc.player != null && mc.gameMode != null) {
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, target.menuSlot(), 0,
                    ContainerInput.PICKUP, mc.player);
        } else if (action == OpenAction.LOCKED) {
            Toast.warn("Storage", "Page " + request.page + " is locked");
        } else if (action == OpenAction.EXPIRED) {
            Toast.warn("Storage", "Page " + request.page + " is not available in the Storage menu");
        }
    }

    static Layout layout(int width, int height, int pages) {
        int cols = Math.max(1, Math.min(3, (width - 20 + GAP) / (CARD_W + GAP)));
        int gridW = cols * CARD_W + (cols - 1) * GAP;
        int visibleRows = Math.max(1, (height - TOP - BOTTOM + GAP) / (CARD_H + GAP));
        int totalRows = (pages + cols - 1) / cols;
        return new Layout(cols, visibleRows, totalRows, (width - gridW) / 2);
    }

    record Layout(int cols, int visibleRows, int totalRows, int left) {
        int maxScroll() {
            return Math.max(0, totalRows - visibleRows);
        }

        int x(int col) {
            return left + col * (CARD_W + GAP);
        }

        int y(int row, int firstRow) {
            return TOP + (row - firstRow) * (CARD_H + GAP);
        }
    }

    private static void drawRoot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        if (!StoragePreview.overviewEnabled()) return;
        g.nextStratum();
        if (!active(screen)) {
            button(g, screen.width - 91, 8, 79, 18, "Overview", false);
            return;
        }

        Font font = Minecraft.getInstance().font;
        List<Integer> pages = pages(screen);
        Layout l = layout(screen.width, screen.height, pages.size());
        scrollRow = StoragePreview.clamp(scrollRow, 0, l.maxScroll());
        // One backdrop controls the whole view. Card and slot fills used to pile
        // different surfaces on top of it, so the opacity was impossible to predict.
        if (backgroundOpacity > 0)
            Draw.rect(g, 0, 0, screen.width, screen.height,
                    Draw.alpha(Theme.bg(), (float) (backgroundOpacity / 100)));
        Draw.rect(g, 12, TOP - 8, screen.width - 24, 1, Draw.alpha(Theme.text(), .13f));
        Draw.text(g, font, "Storage", 12, 10, Theme.text());
        button(g, screen.width - 91, 6, 79, 15, "Vanilla view",
                within(mx, my, screen.width - 91, 6, 79, 15));

        int searchX = screen.width - 174;
        boolean searching = !StorageSearch.query().isBlank();
        Draw.roundedOutline(g, searchX - 5, 22, 168, 20, 4,
                searching ? Theme.obs() : search != null && search.isFocused() ? Theme.accent() : Theme.line(),
                Theme.input());
        if (search != null) {
            search.setTextColor(Theme.text());
            search.extractWidgetRenderState(g, mx, my, 0f);
        }

        if (pages.isEmpty()) {
            Draw.textCentered(g, font, "No storage pages found", screen.width / 2,
                    screen.height / 2 - 5, Theme.muted());
            return;
        }

        String query = StorageSearch.query().trim();
        for (int i = scrollRow * l.cols(); i < pages.size(); i++) {
            int row = i / l.cols();
            if (row >= scrollRow + l.visibleRows()) break;
            int x = l.x(i % l.cols());
            int y = l.y(row, scrollRow);
            int page = pages.get(i);
            drawPage(g, font, page, StoragePreview.snapshots().get(page), query,
                    x, y, within(mx, my, x, y, CARD_W, CARD_H), mx, my);
        }

        int first = scrollRow * l.cols() + 1;
        int last = Math.min(pages.size(), (scrollRow + l.visibleRows()) * l.cols());
        String range = first + "-" + last + " of " + pages.size();
        int fy = screen.height - 20;
        Draw.textCentered(g, font, range, screen.width / 2, fy + 4, Theme.muted());
        int center = screen.width / 2;
        iconButton(g, center - 75, fy, "<", scrollRow > 0);
        iconButton(g, center + 55, fy, ">", scrollRow < l.maxScroll());
        if (within(mx, my, center - 75, fy, 20, 18))
            g.setTooltipForNextFrame(font, Component.literal("Previous pages"), mx, my);
        if (within(mx, my, center + 55, fy, 20, 18))
            g.setTooltipForNextFrame(font, Component.literal("Next pages"), mx, my);

        // Fabric's afterExtract runs after vanilla has flushed deferred tooltips.
        // Flush ours here so item details sit above the replacement view.
        g.extractDeferredElements(mx, my, 0f);
    }

    private static void drawPage(GuiGraphicsExtractor g, Font font, int page, PageSnapshot snap,
                                 String query, int x, int y, boolean hovered, int mx, int my) {
        boolean ec = page == StoragePreview.ENDER_CHEST;
        MenuPage target = menuPages.get(page);
        PageAccess access = ec ? PageAccess.OPEN : target == null ? PageAccess.UNKNOWN : target.access();
        BitSet matchingSlots = StoragePreview.searchMatches(snap, query);
        int matches = matchingSlots.cardinality();
        Draw.text(g, font, ec ? "Ender Chest" : "PV " + page, x + GRID_LEFT, y + 3,
                matches > 0 ? Theme.obs() : hovered ? Theme.accent() : Theme.text());
        String status = matches > 0 ? matches + " found" + (access == PageAccess.OPEN ? ""
                : access == PageAccess.LOCKED ? " · locked" : " · unavailable")
                : access == PageAccess.LOCKED ? "Locked" : access == PageAccess.UNKNOWN ? "Unavailable"
                : snap == null ? "Not visited" : query.isEmpty() ? "" : "0 found";
        if (matches > 0) {
            int badgeW = font.width(status) + 8;
            Draw.roundedRect(g, x + CARD_W - GRID_LEFT - badgeW, y, badgeW, 13, 3, Theme.obs());
            Draw.textRight(g, font, status, x + CARD_W - GRID_LEFT - 4, y + 3, Theme.bg());
        } else {
            Draw.textRight(g, font, status, x + CARD_W - GRID_LEFT, y + 3,
                    Draw.lerp(Theme.muted(), Theme.text(), .4f));
        }

        // The ender chest is shorter than a page; it gets the rows it has, top-aligned
        // with the pages beside it, rather than empty cells that would read as free space.
        int rows = snap == null ? 5 : Math.min(5, Math.max(1, snap.rows() - 1));
        int gx = x + GRID_LEFT, gy = y + GRID_TOP;
        int gridColour = Draw.alpha(Theme.text(), .17f);
        for (int col = 0; col <= PageSnapshot.COLS; col++)
            Draw.rect(g, gx + col * CELL, gy, 1, rows * CELL + 1, gridColour);
        for (int row = 0; row <= rows; row++)
            Draw.rect(g, gx, gy + row * CELL, PageSnapshot.COLS * CELL + 1, 1, gridColour);
        boolean itemTooltip = false;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int cx = gx + col * CELL + 1;
                int cy = gy + row * CELL + 1;
                if (snap == null) continue;
                ItemStack stack = snap.at((row + 1) * 9 + col);
                if (stack.isEmpty()) continue;
                boolean match = matchingSlots.get((row + 1) * 9 + col);
                if (match) Draw.rect(g, cx - 1, cy - 1, CELL, CELL, Draw.alpha(Theme.obs(), .3f));
                g.item(stack, cx, cy);
                g.itemDecorations(font, stack, cx, cy);
                if (match) {
                    matchOutline(g, cx - 1, cy - 1);
                } else if (!query.isEmpty()) {
                    // Keep the page's spatial layout while making its hits the first
                    // thing the eye finds, even over bright enchanted item icons.
                    Draw.rect(g, cx, cy, 16, 16, Draw.alpha(Theme.bg(), .72f));
                }
                if (within(mx, my, cx, cy, 16, 16)) {
                    if (!match) slotOutline(g, cx - 1, cy - 1, Draw.alpha(Theme.text(), .7f));
                    g.setTooltipForNextFrame(font, stack, mx, my);
                    itemTooltip = true;
                }
            }
        }
        if (hovered && !itemTooltip) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(ec ? "Open Ender Chest" : access == PageAccess.OPEN ? "Open PV " + page
                    : access == PageAccess.LOCKED ? "Locked storage page" : "Page unavailable in the current Storage menu"));
            tip.add(Component.literal(snap == null ? "Visit this page to remember its items"
                    : "Last seen " + snap.age(System.currentTimeMillis())).withColor(Theme.muted() & 0xFFFFFF));
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mx, my);
        }
    }

    private static void slotOutline(GuiGraphicsExtractor g, int x, int y, int colour) {
        Draw.rect(g, x, y, CELL + 1, 1, colour);
        Draw.rect(g, x, y + CELL, CELL + 1, 1, colour);
        Draw.rect(g, x, y, 1, CELL + 1, colour);
        Draw.rect(g, x + CELL, y, 1, CELL + 1, colour);
    }

    private static void matchOutline(GuiGraphicsExtractor g, int x, int y) {
        // Two contrasting edges survive both dark and bright icons. A one-pixel
        // accent outline disappeared against the red glass and enchanted glint.
        slotOutline(g, x, y, Theme.obs());
        int edge = Draw.lerp(Theme.obs(), Theme.text(), .75f);
        Draw.rect(g, x + 1, y + 1, CELL - 1, 1, edge);
        Draw.rect(g, x + 1, y + CELL - 1, CELL - 1, 1, edge);
        Draw.rect(g, x + 1, y + 1, 1, CELL - 1, edge);
        Draw.rect(g, x + CELL - 1, y + 1, 1, CELL - 1, edge);
    }

    private static void drawBack(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        if (!StoragePreview.overviewEnabled()) return;
        g.nextStratum();
        boolean hovered = backButton(screen, mx, my);
        button(g, screen.width - 91, 8, 79, 18, "All pages", hovered);
    }

    private static void button(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               String label, boolean hovered) {
        if (hovered) Draw.rect(g, x, y, w, h, Draw.alpha(Theme.hover(), .7f));
        Draw.textCentered(g, Minecraft.getInstance().font, label, x + w / 2,
                y + (h - 8) / 2, hovered ? Theme.accent() : Theme.text());
    }

    private static void iconButton(GuiGraphicsExtractor g, int x, int y, String icon, boolean enabled) {
        Draw.textCentered(g, Minecraft.getInstance().font, icon, x + 10, y + 4,
                enabled ? Theme.text() : Theme.dim());
    }

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        if (!StoragePreview.overviewEnabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (backButton(screen, mx, my) && button == 0) {
            pendingPage = null;
            if (mc.getConnection() != null) mc.getConnection().sendCommand("storage");
            return true;
        }
        if (button == 0 && isPage(screen) && clickedGoBack(screen, mx, my)) {
            pendingPage = null;
            if (mc.getConnection() != null) mc.getConnection().sendCommand("storage");
            return true;
        }
        if (!isRoot(screen)) return false;
        if (!active(screen)) {
            if (button == 0 && within(mx, my, screen.width - 91, 8, 79, 18)) {
                vanilla = false;
                StorageSearch.suspend();
                return true;
            }
            return false;
        }
        if (button != 0) return true;

        if (within(mx, my, screen.width - 91, 6, 79, 15)) {
            pendingPage = null;
            vanilla = true;
            if (search != null) search.setFocused(false);
            return true;
        }
        if (within(mx, my, screen.width - 177, 23, 164, 18)) {
            if (search != null) search.setFocused(true);
            return true;
        }
        if (search != null) search.setFocused(false);

        List<Integer> pages = pages(screen);
        Layout l = layout(screen.width, screen.height, pages.size());
        int fy = screen.height - 20;
        int center = screen.width / 2;
        if (within(mx, my, center - 75, fy, 20, 18)) {
            moveRows(screen, -1);
            return true;
        }
        if (within(mx, my, center + 55, fy, 20, 18)) {
            moveRows(screen, 1);
            return true;
        }
        for (int i = scrollRow * l.cols(); i < pages.size(); i++) {
            int row = i / l.cols();
            if (row >= scrollRow + l.visibleRows()) break;
            if (!within(mx, my, l.x(i % l.cols()), l.y(row, scrollRow), CARD_W, CARD_H)) continue;
            int page = pages.get(i);
            if (page == StoragePreview.ENDER_CHEST) {
                pendingPage = null;
                if (mc.getConnection() != null) mc.getConnection().sendCommand("ec");
            } else {
                openPage(screen, page);
            }
            return true;
        }
        return true; // The server's slots remain underneath the replacement view.
    }

    private static boolean scroll(AbstractContainerScreen<?> screen, double dy) {
        if (!active(screen)) return false;
        if (dy != 0) moveRows(screen, dy > 0 ? -1 : 1);
        return true;
    }

    private static void moveRows(AbstractContainerScreen<?> screen, int delta) {
        Layout l = layout(screen.width, screen.height, pages(screen).size());
        scrollRow = StoragePreview.clamp(scrollRow + delta, 0, l.maxScroll());
    }

    private static boolean within(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean clickedGoBack(AbstractContainerScreen<?> screen, double mx, double my) {
        Container playerInv = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInv || slot.getItem().isEmpty()) continue;
            if (!StoragePreview.plainText(slot.getItem().getHoverName()).equalsIgnoreCase("Go Back")) continue;
            if (within(mx, my, screen.leftPos + slot.x - 1, screen.topPos + slot.y - 1, 18, 18)) {
                return true;
            }
        }
        return false;
    }
}
