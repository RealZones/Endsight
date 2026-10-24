package com.endsight.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** One tintable Lucide atlas: navigation never inherits resource-pack item models or colors. */
final class MenuIcons {
    private MenuIcons() { }
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("endsight", "textures/gui/navigation.png");
    private static final int CELL = 32, COUNT = 24;

    private static void icon(GuiGraphicsExtractor g, int index, int x, int y, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, (float) index * CELL, 0f,
                12, 12, CELL, CELL, CELL * COUNT, CELL, color);
    }

    enum Arrow { UP, RIGHT, DOWN, LEFT }
    static void arrow(GuiGraphicsExtractor g, int x, int y, Arrow direction, int color) {
        icon(g, 16 + direction.ordinal(), x, y, color);
    }
    static void star(GuiGraphicsExtractor g, int x, int y, int color) { icon(g, 1, x, y, color); }
    static void search(GuiGraphicsExtractor g, int x, int y, int color) { icon(g, 20, x, y, color); }
    static void draw(GuiGraphicsExtractor g, String category, int x, int y, int color) {
        int index = switch (category == null ? "" : category) {
            case ModuleRegistry.FAVORITES -> 1;
            case "Slayers" -> 2;
            case "The End" -> 3;
            case "Dungeons" -> 4;
            case "Mining" -> 5;
            case "Storage", "Inventory" -> 6;
            case "Chat" -> 7;
            case "General", "Quality of Life" -> 8;
            case "Edit HUD" -> 9;
            case "Appearance" -> 10;
            case "Tools" -> 12;
            case "Visual" -> 14;
            case "Alerts" -> 21;
            case "Trackers" -> 8;
            case "Social" -> 23;
            default -> 0;
        };
        icon(g, index, x, y, color);
    }
}
