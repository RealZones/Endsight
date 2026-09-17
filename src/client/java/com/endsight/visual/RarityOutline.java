package com.endsight.visual;

import com.endsight.qol.Rarity;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Every item, in every window and on the hotbar, with a ring of its rarity's colour
 * on the slot's border - the one pixel round an item that nothing ever covers.
 *
 * Drawn from inside the game's own slot drawing - {@code extractSlot} on the container
 * screen and on the HUD - just before it draws the item, so the colour sits behind the
 * icon and under any tooltip. The first version drew after the screen was done: the
 * rings landed on top of tooltips, and there was nothing to draw on the hotbar with.
 * That is why this is the one place the public mod uses mixins.
 *
 * The colour is the item's own, read off the last line of its lore the way the drop
 * readouts colour names - so it is what the server says the item is, not a guess.
 */
public final class RarityOutline {

    private RarityOutline() {
    }

    private static boolean enabled = true;
    private static double opacity = 0.85;

    public static Module module() {
        return new Module("visual.rarity", "Rarity Outlines",
                "Rarity colour round every item, in windows and on the hotbar.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Opacity", "How solid the outline is.",
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
        frame(g, x - 1, y - 1, 18, 18, Draw.alpha(colour | 0xFF000000, (float) opacity));
    }

    /** A one-pixel ring with its corner pixels left out, which is what makes it read rounded. */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
        Draw.rect(g, x + 1, y, w - 2, 1, colour);
        Draw.rect(g, x + 1, y + h - 1, w - 2, 1, colour);
        Draw.rect(g, x, y + 1, 1, h - 2, colour);
        Draw.rect(g, x + w - 1, y + 1, 1, h - 2, colour);
    }
}
