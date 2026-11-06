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
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the server's Storage menu offers, and how to get from one page to another.
 *
 * Drawing is all in {@link LiveStorageView}: the Storage menu is that same view with no page
 * open. It used to be a screen of its own, built to look alike, which gave every page to the
 * grid and then lost one to the inventory the moment a page opened, so clicking moved things.
 */
public final class StorageOverview {

    private StorageOverview() {
    }

    private static Screen rootScreen;
    private static boolean vanilla;
    private static double backgroundOpacity = 90;
    private static AbstractContainerScreen<?> pagesScreen;
    private static List<Integer> pageCache = List.of();
    private static Map<Integer, MenuPage> menuPages = Map.of();
    private static PageRequest pendingPage;
    /** A click on a stack in a page that was not open, waiting for that page. */
    private static SlotClick pendingClick;
    private record SlotClick(int page, int slot, ItemStack expected, boolean shift, long until) {}
    /** Where a click is headed while the Storage menu is in between, and how long to believe it. */
    private static int transitPage = -1;
    private static long transitUntil;

    public static Setting opacitySetting() {
        return new Setting.Slider("Overview opacity", "Darkness behind the all-page storage view; zero is clear.",
                0, 100, 5, () -> backgroundOpacity, value -> backgroundOpacity = value, "%");
    }

    public static boolean active(Screen screen) {
        return LiveStorageView.active(screen);
    }

    static boolean vanillaView() { return vanilla; }
    static boolean inTransit() { return transitPage >= 0 && System.currentTimeMillis() < transitUntil; }
    /** The page on its way, or -1. */
    static int transitPage() { return inTransit() ? transitPage : -1; }
    static double opacity() { return backgroundOpacity; }
    static void showVanilla(AbstractContainerScreen<?> screen) {
        vanilla = true;
        pendingPage = null;
        transitPage = -1;
        LiveStorageView.restore(screen);
        StorageSearch.resume();
    }

    static boolean backButton(AbstractContainerScreen<?> screen, double mx, double my) {
        return StoragePreview.overviewEnabled() && isPage(screen)
                && within(mx, my, screen.width - 91, 8, 79, 18);
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageOverview::tickPageRequest);
        ClientTickEvents.END_CLIENT_TICK.register(StorageOverview::tickSlotClick);
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> container) LiveStorageView.restore(container);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            boolean root = isRoot(container);
            if (!root && !isPage(container)) return;

            if (root) {
                pagesScreen = null;
                rootScreen = screen;
                vanilla = false;
                StorageSearch.suspend();
                // Server menu updates arrive on ticks. Page discovery parses every
                // slot name and sorts it; rendering and hit testing share that work.
                ScreenEvents.afterTick(screen).register(s -> pagesScreen = null);
            }
            LiveStorageView.init(container);

            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                if (LiveStorageView.active(s)) return;
                if (isRoot(container)) drawRoot(container, g);
                else drawBack(container, g, mx, my);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
                    !click(container, click.x(), click.y(), click.button(), click.hasShiftDown()));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, hx, vy) ->
                    !scroll(container, vy));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) ->
                    !LiveStorageView.active(s) || !LiveStorageView.key(container, event));
            ScreenKeyboardEvents.allowCharType(screen).register((s, event) ->
                    !LiveStorageView.active(s) || !LiveStorageView.character(event));
            ScreenEvents.remove(screen).register(s -> {
                LiveStorageView.restore(container);
                if (rootScreen == s) {
                    rootScreen = null;
                    transitPage = -1;
                    pagesScreen = null;
                    pageCache = List.of();
                    menuPages = Map.of();
                }
            });
        });
    }

    private static boolean isRoot(AbstractContainerScreen<?> screen) {
        return StoragePreview.isRoot(screen);
    }

    /** A storage page, or the ender chest - both get the way back to the overview. */
    private static boolean isPage(AbstractContainerScreen<?> screen) {
        return (StoragePreview.isStorageWindow(screen) && !isRoot(screen)) || StoragePreview.isEnderChest(screen);
    }

    /** Every page, the ender chest first: it is the one storage everybody has. */
    static List<Integer> pages(AbstractContainerScreen<?> screen) {
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

    /**
     * What a card should say about a page it cannot open, or nothing.
     *
     * Nothing until the menu lists some pages, because no pages listed means its items are
     * still on the way, not that none are open. That gap marked every card Unavailable for a
     * moment each time Storage opened. Nothing while a page is on its way either: the menu
     * is only being passed through, and what it has loaded so far is nobody's business.
     */
    static String accessLabel(int page) {
        if (page == StoragePreview.ENDER_CHEST || menuPages.isEmpty() || inTransit()) return "";
        MenuPage target = menuPages.get(page);
        if (target == null || target.access() == PageAccess.UNKNOWN) return "Unavailable";
        return target.access() == PageAccess.LOCKED ? "Locked" : "";
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
        boolean root = isRoot(source);
        // Said now: waiting on a page a loaded menu does not have would freeze the view for five seconds.
        if (root && !menuPages.isEmpty() && !menuPages.containsKey(page)) {
            Toast.warn("Storage", "Page " + page + " is not available in the Storage menu");
            return;
        }
        long now = System.currentTimeMillis();
        pendingPage = new PageRequest(page, mc.level, source, now);
        transitPage = page;
        transitUntil = now + 5_000;
        if (root) tickPageRequest(mc);
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
        // Only a sent click has anywhere left to go, and not forever if the server ignores it.
        if (action == OpenAction.CLICK) transitUntil = System.currentTimeMillis() + 2_000;
        else transitPage = -1;
        if (action == OpenAction.CLICK && mc.player != null && mc.gameMode != null) {
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, target.menuSlot(), 0,
                    ContainerInput.PICKUP, mc.player);
            // The game guesses the click picked the button up, and on a real server the answer
            // is half a second away: Fear's clip shows the page's glass pane riding the cursor
            // from 3.27 s until the page arrived. The click has gone, so drop the guess.
            screen.getMenu().setCarried(ItemStack.EMPTY);
        } else if (action == OpenAction.LOCKED) {
            Toast.warn("Storage", "Page " + request.page + " is locked");
        } else if (action == OpenAction.EXPIRED) {
            Toast.warn("Storage", "Page " + request.page + " is not available in the Storage menu");
        }
    }

    /** Remembers the stack a click landed on, or forgets the last one when given nothing. */
    static void clickOnArrival(int page, int slot, ItemStack expected, boolean shift) {
        pendingClick = expected.isEmpty() ? null
                : new SlotClick(page, slot, expected.copy(), shift, System.currentTimeMillis() + 7_000);
    }

    private static void tickSlotClick(Minecraft mc) {
        SlotClick click = pendingClick;
        if (click == null) return;
        if (mc.player == null || mc.gameMode == null || System.currentTimeMillis() > click.until) { pendingClick = null; return; }
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || StoragePreview.currentPage(screen) != click.page) return;
        var menu = screen.getMenu();
        if (click.slot >= menu.slots.size()) { pendingClick = null; return; }
        ItemStack live = menu.slots.get(click.slot).getItem();
        // The window opens before its contents arrive, and an empty slot may only mean that yet.
        if (live.isEmpty()) return;
        pendingClick = null;
        // Saved pages can be out of date, and a click meant for one thing must not move another.
        if (!ItemStack.isSameItem(live, click.expected) || !menu.getCarried().isEmpty()) return;
        mc.gameMode.handleContainerInput(menu.containerId, click.slot, 0,
                click.shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
    }

    /** The server's own Storage menu, after Vanilla view: the way back to ours. */
    private static void drawRoot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!StoragePreview.overviewEnabled()) return;
        g.nextStratum();
        button(g, screen.width - 91, 8, 79, 18, "Overview", false);
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

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button, boolean shift) {
        if (!StoragePreview.overviewEnabled()) return false;
        // Only passing through: a click here would land on a menu about to be replaced.
        if (inTransit() && isRoot(screen)) return true;
        if (LiveStorageView.active(screen)) return LiveStorageView.click(screen, mx, my, button, shift);
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
        if (button == 0 && within(mx, my, screen.width - 91, 8, 79, 18)) {
            vanilla = false;
            StorageSearch.suspend();
            return true;
        }
        return false;
    }

    private static boolean scroll(AbstractContainerScreen<?> screen, double dy) {
        if (!LiveStorageView.active(screen)) return false;
        if (dy != 0) LiveStorageView.move(screen, dy > 0 ? -1 : 1);
        return true;
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
