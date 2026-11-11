package com.endsight.storage;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The recipe panel beside the inventory, from the bundled list alone - a fresh config, as a
 * new player has it - on All, then the mod's own Dungeons and Rift groups. Run at the real
 * window, 1920x1009 at GUI scale 2.
 */
public final class RecipesVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1920, 1009);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(2);
                mc.resizeGui();
            });
            context.waitTicks(40);                       // icons load once the level is up
            context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            context.waitTicks(10);
            for (String c : new String[]{null, "Dungeons", "Rift"}) {
                context.runOnClient(mc -> show(c));
                context.waitTicks(5);
                context.takeScreenshot("recipes-" + (c == null ? "all" : c.toLowerCase()));
            }
            // A recipe's tooltip for an item with Dragsim's tooltip font in its lore (every
            // recipe added after the wipe has it). It must keep that font: drawn as plain text
            // it was a screen-wide row of missing-glyph boxes. With Dragsim's pack loaded in
            // the run folder the pills and separators draw for real; without it, boxes again.
            context.runOnClient(mc -> {
                ItemStack icon = icons().get("Dark Claymore");
                if (icon == null) throw new AssertionError("no Dark Claymore picture bundled");
                java.util.List<?> lines = tip(icon);
                boolean font = lines.stream().anyMatch(l -> ((Component) l).toFlatList().stream()
                        .anyMatch(part -> part.getStyle().getFont().toString().contains("dragsim")));
                if (!font) throw new AssertionError("the tooltip lost Dragsim's font: " + lines);
            });
            context.takeScreenshot("recipes-tooltip-new-lore");
            context.runOnClient(mc -> mc.setScreen(null));
            System.out.println("RECIPES VISUAL CHECKS DONE");
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, ItemStack> icons() {
        try {
            var f = Recipes.class.getDeclaredField("ICONS");
            f.setAccessible(true);
            return (java.util.Map<String, ItemStack>) f.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** The panel's own hover path for a cell, left pending for the next frame to draw. */
    private static java.util.List<?> tip(ItemStack icon) {
        try {
            var m = Recipes.class.getDeclaredMethod("itemTip", ItemStack.class, int.class, int.class);
            m.setAccessible(true);
            m.invoke(null, icon, 700, 200);
            var f = Recipes.class.getDeclaredField("pendingTip");
            f.setAccessible(true);
            return (java.util.List<?>) f.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void show(String category) {
        try {
            var cat = Recipes.class.getDeclaredField("cat");
            cat.setAccessible(true);
            cat.set(null, category);
            var page = Recipes.class.getDeclaredField("page");
            page.setAccessible(true);
            page.setInt(null, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
