package com.endsight.storage;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
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
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** A full-page index over the server's Storage menu, backed by visited page snapshots. */
public final class StorageOverview {

    private StorageOverview() {
    }

    static final int CARD_W = 186;
    static final int CARD_H = 128;
    static final int GAP = 10;
    private static final int CELL = 18;
    private static final int GRID_TOP = 30;
    private static final int TOP = 52;
    private static final int BOTTOM = 28;

    private static Screen rootScreen;
    private static boolean vanilla;
    private static EditBox search;
    private static int scrollRow;

    public static boolean active(Screen screen) {
        return screen == rootScreen && !vanilla && StoragePreview.overviewEnabled();
    }

    static boolean backButton(AbstractContainerScreen<?> screen, double mx, double my) {
        return StoragePreview.overviewEnabled() && isPage(screen)
                && within(mx, my, screen.width - 91, 8, 79, 18);
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            boolean root = isRoot(container);
            if (!root && !isPage(container)) return;

            if (root) {
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
                return true;
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

    /** Every card, the ender chest first: it is the one storage everybody has. */
    private static List<Integer> pages(AbstractContainerScreen<?> screen) {
        Set<Integer> found = new TreeSet<>();
        found.add(StoragePreview.ENDER_CHEST);
        Container playerInv = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInv) continue;
            int page = StoragePreview.pageOfItem(slot.getItem());
            if (page > 0 && page <= 54) found.add(page);
        }
        int count = 0;
        for (PageSnapshot snap : StoragePreview.snapshots().values()) {
            count = Math.max(count, Math.max(snap.page(), snap.pageCount()));
        }
        count = Math.min(count, 54);
        for (int p = 1; p <= count; p++) found.add(p);
        return new ArrayList<>(found);
    }

    static Layout layout(int width, int height, int pages) {
        int cols = Math.max(1, Math.min(4, (width - 24 + GAP) / (CARD_W + GAP)));
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
        int cached = 0;
        for (int page : pages) if (page != StoragePreview.ENDER_CHEST && StoragePreview.hasSnapshot(page)) cached++;

        Draw.rect(g, 0, 0, screen.width, screen.height, Theme.bg());
        Draw.rect(g, 0, 0, screen.width, TOP - 7, Theme.surface());
        Draw.rect(g, 0, TOP - 8, screen.width, 1, Theme.line());
        Draw.text(g, font, "STORAGE", 12, 7, Theme.text());
        String status = (pages.size() - 1) + " pages  /  " + cached + " scanned";
        Draw.text(g, font, Draw.fit(font, status, Math.max(40, screen.width - 194)),
                12, 25, Theme.dim());
        button(g, screen.width - 91, 6, 79, 15, "Vanilla view",
                within(mx, my, screen.width - 91, 6, 79, 15));

        int searchX = screen.width - 174;
        Draw.roundedOutline(g, searchX - 3, 23, 164, 18, 5,
                search != null && search.isFocused() ? Theme.accent() : Theme.line(), Theme.input());
        if (search != null) search.extractWidgetRenderState(g, mx, my, 0f);

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
        int border = hovered ? Theme.accent() : Theme.line();
        Draw.roundedOutline(g, x, y, CARD_W, CARD_H, 6, border, Theme.surface());
        if (hovered) Draw.rect(g, x + 1, y + 5, 2, CARD_H - 10, Theme.accent());
        boolean ec = page == StoragePreview.ENDER_CHEST;
        Draw.text(g, font, ec ? "ENDER CHEST" : "PAGE " + String.format("%02d", page), x + 12, y + 6,
                hovered ? Theme.accent() : Theme.text());

        if (snap == null) {
            Draw.textRight(g, font, "NOT SCANNED", x + CARD_W - 12, y + 6, Theme.muted());
            Draw.text(g, font, "Open to scan", x + 12, y + 18, Theme.dim());
        } else {
            int filled = 0;
            for (int i = PageSnapshot.COLS; i < snap.items().size(); i++) {
                if (!snap.at(i).isEmpty()) filled++;
            }
            int capacity = Math.max(0, snap.items().size() - PageSnapshot.COLS);
            Draw.textRight(g, font, filled + "/" + capacity,
                    x + CARD_W - 12, y + 6, Theme.muted());
            String age = snap.age(System.currentTimeMillis());
            int matches = query.isEmpty() ? 0 : StoragePreview.matchesIn(page, query);
            String detail = query.isEmpty() ? age : matches + (matches == 1 ? " match" : " matches") + "  /  " + age;
            Draw.text(g, font, Draw.fit(font, detail, CARD_W - 24), x + 12, y + 18,
                    matches > 0 ? Theme.accent() : Theme.dim());
        }

        // The ender chest is shorter than a page; it gets the rows it has, top-aligned
        // with the pages beside it, rather than empty cells that would read as free space.
        int rows = snap == null ? 5 : Math.min(5, Math.max(1, snap.rows() - 1));
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int cx = x + 12 + col * CELL;
                int cy = y + GRID_TOP + row * CELL;
                Draw.rect(g, cx, cy, 16, 16, Theme.input());
                if (snap == null) continue;
                ItemStack stack = snap.at((row + 1) * 9 + col);
                if (stack.isEmpty()) continue;
                g.item(stack, cx, cy);
                g.itemDecorations(font, stack, cx, cy);
                if (!query.isEmpty() && StorageSearch.matches(stack, query)) {
                    Draw.roundedRing(g, cx - 1, cy - 1, 18, 18, 2, Theme.accent());
                }
                if (within(mx, my, cx, cy, 16, 16)) {
                    Draw.roundedRing(g, cx - 1, cy - 1, 18, 18, 2, Theme.line());
                    g.setTooltipForNextFrame(font, stack, mx, my);
                }
            }
        }
        if (hovered && !within(mx, my, x + 12, y + GRID_TOP, 162, 90)) {
            g.setTooltipForNextFrame(font, Component.literal(ec ? "Open Ender Chest" : "Open Storage Page " + page), mx, my);
        }
    }

    private static void drawBack(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        if (!StoragePreview.overviewEnabled()) return;
        g.nextStratum();
        boolean hovered = backButton(screen, mx, my);
        button(g, screen.width - 91, 8, 79, 18, "All pages", hovered);
    }

    private static void button(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               String label, boolean hovered) {
        Draw.roundedOutline(g, x, y, w, h, 5,
                hovered ? Theme.accent() : Theme.line(), hovered ? Theme.hover() : Theme.raised());
        Draw.textCentered(g, Minecraft.getInstance().font, label, x + w / 2,
                y + (h - 8) / 2, Theme.text());
    }

    private static void iconButton(GuiGraphicsExtractor g, int x, int y, String icon, boolean enabled) {
        Draw.roundedOutline(g, x, y, 20, 18, 5, enabled ? Theme.line() : Theme.hair(), Theme.raised());
        Draw.textCentered(g, Minecraft.getInstance().font, icon, x + 10, y + 4,
                enabled ? Theme.text() : Theme.dim());
    }

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        if (!StoragePreview.overviewEnabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (backButton(screen, mx, my) && button == 0) {
            if (mc.getConnection() != null) mc.getConnection().sendCommand("storage");
            return true;
        }
        if (button == 0 && isPage(screen) && clickedGoBack(screen, mx, my)) {
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
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand(page == StoragePreview.ENDER_CHEST ? "ec" : "storage " + page);
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
