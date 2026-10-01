package com.endsight.visual;

import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.List;

/** Backdrops only. Slot interaction, recipe books and the player portrait remain vanilla. */
public final class InventoryGlass {
    private static boolean enabled;
    private static double opacity = .6;

    private InventoryGlass() {}

    public static Module module() {
        return new Module("visual.inventoryGlass", "Glass Inventory", "Glass inventory and chest backgrounds.", "Visual",
                () -> enabled, value -> enabled = value,
                List.of(new Setting.Slider("Opacity", "Darkness behind the slots; zero is clear.",
                        0, .6, .05, () -> opacity, value -> opacity = value, "")));
    }

    public static boolean applies(Object screen) {
        return enabled && (screen instanceof InventoryScreen || screen instanceof ContainerScreen);
    }

    /**
     * An outline in the theme's colour and nothing inside it.
     *
     * This filled with Theme.bg() and laid a lit edge along the top, which is what made
     * the window read as a tinted sheet in the theme's colour rather than glass. Black at
     * whatever the slider says goes genuinely clear at the bottom of the range; the border
     * carries the shape, so nothing is lost when the fill goes to nothing.
     */
    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, double alpha) {
        if (alpha > 0) Draw.roundedRect(g, x, y, w, h, Theme.RADIUS, Draw.alpha(0xFF000000, (float) alpha));
        Draw.roundedRing(g, x, y, w, h, Theme.RADIUS, Draw.alpha(Theme.accent(), 1f));
    }

    /**
     * Nothing. The slots are deliberately not drawn.
     *
     * A ring around every slot competes with the rarity outline that is already there, and
     * the outline is the thing worth seeing - it carries what the item is. The window's own
     * frame is enough to say where the grid is.
     */
    public static void slot(GuiGraphicsExtractor g, int x, int y) {
    }

    public static void background(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        int x = screen.leftPos, y = screen.topPos;
        frame(g, x, y, screen.imageWidth, screen.imageHeight, opacity);
        for (var slot : screen.getMenu().slots) {
            if (slot.isActive()) slot(g, x + slot.x, y + slot.y);
        }
        if (screen instanceof InventoryScreen) {
            Draw.text(g, net.minecraft.client.Minecraft.getInstance().font, ">", x + 135, y + 35, Theme.muted());
        }
    }
}
