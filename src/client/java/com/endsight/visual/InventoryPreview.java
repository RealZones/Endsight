package com.endsight.visual;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public final class InventoryPreview {
    private static final String ID = "hud.inventoryPreview";
    // No header. The panel is nine columns of item slots - it does not need a label
    // saying so, and the label plus its rule cost 20px of height on a readout whose
    // whole job is to stay out of the way.
    private static final int PAD = 4, CELL = 18, GAP = 6, WIDTH = PAD * 2 + CELL * 9;
    private static final class Samples {
        private static final ItemStack[] STACKS = {new ItemStack(Items.DIAMOND, 12), new ItemStack(Items.OBSIDIAN, 64),
                new ItemStack(Items.AMETHYST_SHARD, 32), new ItemStack(Items.DIAMOND_PICKAXE)};
    }
    private static boolean enabled, hotbar = true;
    private static double opacity = 0;

    private InventoryPreview() {}

    public static Module module() {
        return new Module(ID, "Inventory Preview", "Movable grid of your inventory items.", "Visual",
                () -> enabled, value -> enabled = value, List.of(
                new Setting.Section("Readout"),
                new Setting.Toggle("Hotbar", "Include the nine hotbar slots.", () -> hotbar, value -> hotbar = value),
                new Setting.Slider("Opacity", "Darkness behind the item grid.", 0, .6, .05, () -> opacity, value -> opacity = value, ""),
                new Setting.Action("Position", "Place the readout on screen.", "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        HudLayout.register(ID, "Inventory Preview", .5f, .8019093f, InventoryPreview::drawAt);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "inventory_preview"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && mc.player != null && mc.level != null && !mc.options.hideGui)
                HudLayout.draw(ID, g, mc.font, false);
        });
    }

    static int inventoryIndex(int row, int column) {
        if (row < 0 || row > 3 || column < 0 || column > 8) throw new IllegalArgumentException("Slot outside preview");
        return row == 3 ? column : 9 + row * 9 + column;
    }

    static int height(boolean withHotbar) {
        return PAD * 2 + CELL * 3 + (withHotbar ? GAP + CELL : 0);
    }

    /**
     * An outline and nothing else.
     *
     * This used to borrow InventoryGlass.frame, which fills with the theme's own
     * background colour - so the readout arrived tinted, and over dark stone it read
     * as a coloured sheet laid on the world rather than a window onto it. The fill is
     * now neutral black at whatever the slider says, which at the bottom of the range
     * is genuinely clear rather than clear-ish, and the border carries the shape.
     */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (opacity > 0) Draw.roundedRect(g, x, y, w, h, Theme.RADIUS, Draw.alpha(0xFF000000, (float) opacity));
        // A pixel is as thin as a line gets, so "thinner" here means fainter: the readout
        // sits over the world and its edge only has to say where it stops.
        Draw.roundedRing(g, x, y, w, h, Theme.RADIUS, Draw.alpha(Theme.accent(), .35f));
    }

    /** Slot outline with no fill, so an empty slot shows the world through it. */
    private static void slot(GuiGraphicsExtractor g, int x, int y) {
        // Kept, but barely: the grid is worth seeing so an empty slot reads as a slot
        // rather than a gap, and anything stronger fights the rarity outlines over it.
        Draw.roundedRing(g, x - 1, y - 1, CELL, CELL, 2, Draw.alpha(Theme.text(), .16f));
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        int h = height(hotbar);
        if (g == null) return new int[]{WIDTH, h};
        Minecraft mc = Minecraft.getInstance();
        frame(g, x, y, WIDTH, h);
        for (int row = 0; row < (hotbar ? 4 : 3); row++) {
            int sy = y + PAD + row * CELL + (row == 3 ? GAP : 0);
            for (int column = 0; column < 9; column++) {
                int sx = x + PAD + column * CELL;
                slot(g, sx, sy);
                int index = inventoryIndex(row, column);
                ItemStack stack = sample ? (index % 7 == 0 ? Samples.STACKS[index % Samples.STACKS.length] : ItemStack.EMPTY)
                        : mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().getItem(index);
                if (row == 3 && mc.player != null && mc.player.getInventory().getSelectedSlot() == column)
                    Draw.roundedOutline(g, sx - 1, sy - 1, CELL, CELL, 2, Theme.accent(), 0);
                if (!stack.isEmpty()) {
                    RarityOutline.behindHotbar(g, sx, sy, stack);
                    g.item(stack, sx, sy);
                    g.itemDecorations(font, stack, sx, sy);
                }
            }
        }
        return new int[]{WIDTH, h};
    }
}
