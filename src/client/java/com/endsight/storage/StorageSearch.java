package com.endsight.storage;

import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
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

import java.util.List;

/**
 * A search box in the Storage window's title bar that marks where an item is.
 *
 * The hard part of this feature is not finding the item, it is answering "where is it"
 * in a way you can act on. A list of hits would be a second thing to read and then
 * translate back into a click. So nothing is listed: matches are ringed where they
 * already are. In a page, the matching slots light up. In the root Storage menu the
 * pages themselves light up - with a count - because the page item IS the thing you
 * would click next. The answer to "where is it" is the button you press.
 *
 * That only works because a page you have opened is already remembered by
 * {@link StoragePreview}; pages never opened cannot be searched, and say so rather
 * than quietly reporting no matches.
 *
 * Text input is a real {@link EditBox} added to the screen's own widget list rather
 * than a box drawn by hand. There is no charTyped event in the screen API, so a
 * hand-rolled box would have to reconstruct characters from key codes - wrong on the
 * first non-US keyboard it met. Vanilla already routes typing to a focused child.
 */
public final class StorageSearch {

    private StorageSearch() {
    }

    // ── module state ──────────────────────────────────────────────────────────
    private static boolean enabled = true;
    private static boolean searchInventory = true;
    private static boolean markUnsearched = true;

    private static EditBox box;
    private static String query = "";

    public static Module module() {
        return new Module("storage.search", "Item Search",
                "Finds an item across every page at once.", "Storage",
                () -> enabled, v -> {
                    enabled = v;
                    // The box is a real widget on a screen that is already open, so
                    // switching the module off has to take it away rather than just
                    // stop drawing it - otherwise it keeps eating keystrokes.
                    if (!v) {
                        query = "";
                        if (box != null) box.setValue("");
                    }
                },
                List.of(
                        new Setting.Toggle("Search your inventory",
                                "Mark matches in your own inventory too.",
                                () -> searchInventory, v -> searchInventory = v),
                        new Setting.Toggle("Mark unopened pages",
                                "Ring pages that have never been opened, so an empty "
                                        + "result is not mistaken for a real answer.",
                                () -> markUnsearched, v -> markUnsearched = v)));
    }

    /** Set while a Storage-ish window is open, so stale boxes never take input. */
    private static Screen owner;

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!enabled) return;
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            if (!StoragePreview.isStorageWindow(container)) return;

            Font font = client.font;
            int bx = container.leftPos + 86;
            int by = container.topPos + 5;
            box = new EditBox(font, bx, by, 82, 11, Component.literal("Search"));
            box.setBordered(false);
            box.setMaxLength(48);
            box.setTextColor(Theme.text());
            box.setHint(Component.literal("Search items…"));
            box.setValue(query);
            box.setResponder(v -> query = v);
            owner = screen;
            Screens.getWidgets(screen).add(box);

            // AbstractContainerScreen closes on the inventory key, and whether it looks
            // at that before or after its focused child is a vanilla detail that has
            // moved between versions. Feeding the box first and hiding the key from the
            // screen makes typing "e" type an "e" either way. Escape is left alone so it
            // still closes the window.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> {
                if (box == null || !box.isFocused() || e.key() == 256) return true;
                box.keyPressed(e);
                return false;
            });

            ScreenEvents.beforeExtract(screen).register((s, g, mx, my, d) -> drawBox(g, bx, by));
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> drawHits(container, g));
            ScreenEvents.remove(screen).register(s -> {
                if (owner == s) {
                    owner = null;
                    box = null;
                }
            });
        });
    }

    /** Our own chrome, drawn before the widget so the vanilla text lands on top of it. */
    private static void drawBox(GuiGraphicsExtractor g, int bx, int by) {
        if (!enabled) return;
        boolean active = !query.isEmpty();
        Draw.roundedOutline(g, bx - 4, by - 3, 90, 17, 5,
                active ? Theme.accent() : Draw.alpha(Theme.line(), 0.8f), Theme.input());
    }

    private static void drawHits(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!enabled || query.isBlank()) return;
        Font font = Minecraft.getInstance().font;
        Container playerInv = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();

        g.nextStratum();

        for (Slot slot : screen.getMenu().slots) {
            int sx = screen.leftPos + slot.x;
            int sy = screen.topPos + slot.y;
            ItemStack stack = slot.getItem();

            // A page item stands for everything inside it, so it answers for its own
            // snapshot rather than for its own name.
            int page = StoragePreview.pageOfItem(stack);
            if (page >= 0) {
                int n = StoragePreview.matchesIn(page, query);
                if (n > 0) {
                    ring(g, sx, sy, Theme.accent());
                    String label = String.valueOf(n);
                    int lw = font.width(label) + 3;
                    Draw.roundedRect(g, sx + 16 - lw, sy - 3, lw, 9, 3, Theme.accent());
                    Draw.text(g, font, label, sx + 17 - lw, sy - 2, Theme.bg());
                } else if (markUnsearched && !StoragePreview.hasSnapshot(page)) {
                    // Never opened, so genuinely unknown - not the same as "not here".
                    ring(g, sx, sy, Draw.alpha(Theme.muted(), 0.55f));
                }
                continue;
            }

            boolean mine = slot.container == playerInv;
            if ((!mine || searchInventory) && matches(stack)) {
                ring(g, sx, sy, mine ? Theme.pos() : Theme.accent());
            }
        }
    }

    /** A ring around a slot rather than a wash over it, so the item stays readable. */
    private static void ring(GuiGraphicsExtractor g, int x, int y, int color) {
        Draw.rect(g, x - 1, y - 1, 18, 1, color);
        Draw.rect(g, x - 1, y + 16, 18, 1, color);
        Draw.rect(g, x - 1, y, 1, 16, color);
        Draw.rect(g, x + 16, y, 1, 16, color);
    }

    static boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return StoragePreview.plainText(stack.getHoverName())
                .toLowerCase().contains(query.toLowerCase());
    }

    static boolean matches(ItemStack stack, String q) {
        if (stack == null || stack.isEmpty() || q == null || q.isBlank()) return false;
        return StoragePreview.plainText(stack.getHoverName())
                .toLowerCase().contains(q.toLowerCase());
    }
}
