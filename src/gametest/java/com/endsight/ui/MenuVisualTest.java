package com.endsight.ui;

import com.endsight.EndsightClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/** Grouped settings pages as the browser draws them, then the whole menu at the sizes people really play at. */
public final class MenuVisualTest implements FabricClientGameTest {
    /** Window, GUI scale. The first is Fear's: a maximized 1080p window under the taskbar. */
    private static final int[][] WINDOWS = {{1920, 1009, 2}, {2560, 1369, 2}, {1920, 1009, 3}, {1280, 720, 2}, {854, 480, 2}};

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("time set noon");
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(2);
                mc.resizeGui();
            });
            for (String page : new String[]{"slayer tracking", "countdowns", "boss indicators"}) {
                context.runOnClient(mc -> mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry())));
                context.waitTicks(2);
                context.runOnClient(mc -> {
                    for (int i = 0; i < 30; i++) mc.screen.keyPressed(new KeyEvent(259, 0, 0));
                });
                context.getInput().typeChars(page);
                context.waitTicks(2);
                // Enter opens the first result, the way a player gets to a page from search.
                context.runOnClient(mc -> mc.screen.keyPressed(new KeyEvent(257, 0, 0)));
                context.waitTicks(10);
                context.takeScreenshot("menu-" + page.replace(' ', '-'));
            }
            context.runOnClient(mc -> {
                mc.screen.keyPressed(new KeyEvent(256, 0, 0));
                Theme.palettes().stream().filter(p -> p.name().equals("Midnight Violet")).findFirst().ifPresent(Theme::set);
                // Through the real KeyMapping, which is what opens the menu and what Controls shows.
                Keybinds.set(Keybinds.OPEN_MENU, 295);
                if (Keybinds.get(Keybinds.OPEN_MENU) != 295) throw new AssertionError("The menu key did not change");
                Keybinds.set(Keybinds.OPEN_MENU, Keybinds.mouse(3));
                if (Keybinds.get(Keybinds.OPEN_MENU) != Keybinds.mouse(3)) throw new AssertionError("The menu key did not take a mouse button");
                Keybinds.clear(Keybinds.OPEN_MENU);
                if (Keybinds.get(Keybinds.OPEN_MENU) != 344) throw new AssertionError("Clearing the menu key did not restore Right Shift");
            });
            for (int[] window : WINDOWS) {
                context.getInput().resizeWindow(window[0], window[1]);
                context.runOnClient(mc -> { mc.options.guiScale().set(window[2]); mc.resizeGui(); });
                context.waitTicks(4);
                String size = window[0] + "x" + window[1] + "-gui" + window[2];
                view(context, size + "-favorites", ModuleRegistry.FAVORITES, null);
                view(context, size + "-inventory", "Inventory", "page.previews");
                if (window == WINDOWS[0]) {
                    view(context, size + "-appearance", "@appearance", "ui.appearance");
                    // The bottom of the opacity slider, over a bright sky: still has to read.
                    context.runOnClient(mc -> Theme.panelOpacity(.6f));
                    view(context, size + "-opacity-60", "Inventory", "page.previews");
                    context.runOnClient(mc -> Theme.panelOpacity(Theme.DEFAULT_OPACITY));
                    context.runOnClient(mc -> {
                        for (var bind : java.util.Map.of("module.zealot.tracker", 90, "module.dragon.bossdrops", 293).entrySet())
                            Keybinds.set(bind.getKey(), bind.getValue());
                    });
                    view(context, size + "-keybinds", "@keybinds", "ui.keybinds");
                    context.runOnClient(mc -> showAllKeys(true));
                    view(context, size + "-keybinds-all", "@keybinds", "ui.keybinds");
                    context.runOnClient(mc -> showAllKeys(false));
                }
            }
            context.runOnClient(mc -> mc.setScreen(null));
            cost(context);
            System.out.println("MENU VISUAL CHECKS DONE");
        }
    }

    /** What a frame and an idle tick really cost: CPU time and garbage, printed for comparison between builds. */
    private static void cost(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOWS[0][0], WINDOWS[0][1]);
        context.runOnClient(mc -> { mc.options.guiScale().set(WINDOWS[0][2]); mc.resizeGui(); });
        context.waitTicks(4);
        for (String[] page : new String[][]{{ModuleRegistry.FAVORITES, null}, {"Inventory", "page.previews"}}) {
            context.runOnClient(mc -> {
                open(mc, page[0], page[1]);
                mc.setScreen(new Timed());
            });
            context.waitTicks(150);
            context.runOnClient(mc -> System.out.println("COST frame " + page[0] + "/" + page[1] + ": " + ((Timed) mc.screen).report()));
        }
        context.runOnClient(mc -> mc.setScreen(null));
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** The browser, timed around its own drawing. The first frames warm up and are left out. */
    private static final class Timed extends EndsightScreen {
        private int frames;
        private long nanos, bytes;
        Timed() { super("Endsight", EndsightClient.registry()); }
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float delta) {
            long before = allocated(), start = System.nanoTime();
            super.extractRenderState(g, mx, my, delta);
            if (++frames <= 30) return;
            nanos += System.nanoTime() - start;
            bytes += allocated() - before;
        }
        String report() {
            int counted = Math.max(1, frames - 30);
            return counted + " frames, " + nanos / counted / 1000 + " us, " + bytes / counted / 1024 + " KB per frame";
        }
    }

    private static void showAllKeys(boolean all) {
        try {
            var field = KeybindsView.class.getDeclaredField("showAll");
            field.setAccessible(true);
            field.set(null, all);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    /** Lands on a view directly: the browser's own state, not a guess at where its rows ended up. */
    private static void view(ClientGameTestContext context, String name, String category, String expanded) {
        context.runOnClient(mc -> open(mc, category, expanded));
        context.waitTicks(12);
        context.takeScreenshot("look-" + name);
    }

    private static void open(Minecraft mc, String category, String expanded) {
        mc.setScreen(null);
        try {
            set("category", category);
            set("expandedId", expanded);
            set("query", "");
            set("scroll", 0);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
    }

    private static void set(String field, Object value) throws ReflectiveOperationException {
        var f = EndsightScreen.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }
}
