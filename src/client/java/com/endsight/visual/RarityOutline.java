package com.endsight.visual;

import com.endsight.qol.Rarity;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Rarity-coloured highlights behind items, in windows and on the hotbar. Edges and
 * underlines use the slot's border so the icon and item count remain unobstructed.
 *
 * Drawn from inside the game's own slot drawing - {@code extractSlot} on the container
 * screen and on the HUD - just before it draws the item, so the colour sits behind the
 * icon and under any tooltip. The first version drew after the screen was done: the
 * rings landed on top of tooltips, and there was nothing to draw on the hotbar with.
 * That is why this is the one place the public mod uses mixins.
 *
 * The colour comes from the same server item-data reader as the drop readouts,
 * including when the resource pack draws rarity and type in separate tooltip badges.
 */
public final class RarityOutline {

    private RarityOutline() {
    }

    private static boolean enabled = true;
    /*
     * Glow is gone: it was the Outline with a faint second ring outside it, and next to the
     * Outline nobody could tell which was which - Fear included ("idk the diff"). A saved
     * "Glow" is no longer an option, so Config drops it and the player lands on the default.
     * Underline is the default on Fear's call; it is the style he plays with. Every older
     * config has Corners saved, so Config moves that to Underline once (see its migrate).
     */
    private static final List<String> STYLES = List.of("Corners", "Outline", "Pixel", "Underline");
    private static String style = "Underline";
    private static double opacity = 0.75;

    public static Module module() {
        return new Module("visual.rarity", "Rarity Outlines",
                "Rarity colour round every item, in windows and on the hotbar.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Style", "Corners, an outline, a pixel background or an underline.",
                                STYLES, () -> style, v -> style = v),
                        new Setting.Slider("Opacity", "How solid the rarity highlight is.",
                                0.2, 1, 0.05, () -> opacity, v -> opacity = v, "")));
    }

    public static void init() {
    }

    /** Called from the container screen's slot drawing, under the window's own translation. */
    public static void behindSlot(GuiGraphicsExtractor g, Slot slot) {
        behind(g, slot.x, slot.y, slot.getItem());
    }

    /** Called from the HUD's hotbar slot drawing, with the item's own corner. */
    public static void behindHotbar(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        behind(g, x, y, stack);
    }

    private static void behind(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        if (!enabled || stack.isEmpty()) return;
        int colour = Rarity.of(stack);
        if (colour == 0) return;
        int c = Draw.alpha(colour | 0xFF000000, (float) opacity);
        switch (style) {
            case "Corners" -> corners(g, x - 1, y - 1, 18, 18, c);
            case "Outline" -> frame(g, x - 1, y - 1, 18, 18, c);
            case "Pixel" -> pixel(g, x - 1, y - 1, colour | 0xFF000000);
            default -> underline(g, x, y, colour | 0xFF000000);
        }
    }

    /** A one-pixel ring with its corner pixels left out, which is what makes it read rounded. */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
        Draw.frame(g, x, y, w, h, colour);
    }

    /** Four short Ls: enough to identify rarity without boxing every icon in. */
    private static void corners(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
        Draw.corners(g, x, y, w, h, 5, colour);
    }

    private static void pixel(GuiGraphicsExtractor g, int x, int y, int colour) {
        // Two-pixel steps read like Minecraft sprites rather than a smooth circular halo.
        badge(g, x, y, 18, Draw.alpha(colour, (float) opacity * 0.65f));
        badge(g, x + 1, y + 1, 16,
                Draw.alpha(Draw.lerp(Theme.bg(), colour, 0.32f), (float) opacity));
    }

    private static void underline(GuiGraphicsExtractor g, int x, int y, int colour) {
        float strength = (float) opacity;
        g.fillGradient(x - 1, y + 4, x + 17, y + 16,
                colour & 0xFFFFFF, Draw.alpha(colour, strength * .45f));
        g.fill(x, y + 16, x + 16, y + 17, Draw.alpha(colour, strength));
        // Span the full 18-pixel slot, with softer tips so neighbouring items do
        // not read as one continuous stripe when they share a rarity.
        int tip = Draw.alpha(colour, strength * .4f);
        g.fill(x - 1, y + 16, x, y + 17, tip);
        g.fill(x + 16, y + 16, x + 17, y + 17, tip);
    }

    private static void badge(GuiGraphicsExtractor g, int x, int y, int size, int colour) {
        Draw.badge(g, x, y, size, colour);
    }
}
