package com.endsight.storage;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A real integrated-server menu verifies that the overview transfers real items. */
public final class LiveStorageVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2); mc.resizeGui();
                StoragePreview.module().setEnabled().accept(true);
                List<ItemStack> cached = new ArrayList<>();
                for (int i=0;i<54;i++) cached.add(i>=9 && i<20 ? new ItemStack(Items.AMETHYST_SHARD, i) : ItemStack.EMPTY);
                // Rarities on a page that is not open: outlines have to work from the saved stack.
                cached.get(9).set(DataComponents.LORE, new ItemLore(List.of(Component.literal("LEGENDARY SWORD"))));
                cached.get(10).set(DataComponents.LORE, new ItemLore(List.of(Component.literal("MYTHIC HELMET"))));
                cached.get(11).set(DataComponents.LORE, new ItemLore(List.of(Component.literal("RARE BOOTS"))));
                StoragePreview.snapshots().put(2, new PageSnapshot(2,7,cached,System.currentTimeMillis()));
            });
            cost(context, world);
            transit(context, world);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 4));
                var chest = new SimpleContainer(54);
                for(int i=0;i<9;i++) chest.setItem(i,new ItemStack(Items.GRAY_STAINED_GLASS_PANE));
                chest.setItem(9,new ItemStack(Items.DIAMOND,16));
                chest.setItem(10,new ItemStack(Items.GOLD_INGOT,8));
                player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id,inv,chest),
                        Component.literal("Storage Page (1/7)")));
            });
            context.waitTicks(10);
            context.runOnClient(mc -> {
                require(mc.screen instanceof AbstractContainerScreen<?>, "Server menu did not open");
                var screen=(AbstractContainerScreen<?>)mc.screen;
                require(LiveStorageView.active(screen), "PV did not retain the custom overview");
                LiveStorageView.prepare(screen);
                require(screen.getMenu().slots.get(9).x>=0 && screen.getMenu().slots.get(0).x<0,
                        "Live stash or hidden server controls were mapped incorrectly");
                // Page 1 of 7: the ender chest first, then this page, in its own place.
                var layout=LiveStorageView.layout(screen);
                require(screen.getMenu().slots.get(9).x==layout.x(1)+3 && screen.getMenu().slots.get(9).y==layout.y(1)+18,
                        "The open page left its place among the others");
                mc.getToastManager().clear();
            });
            context.takeScreenshot("storage-live-overview");
            int[] pageSlot=context.computeOnClient(mc -> position(((AbstractContainerScreen<?>)mc.screen).getMenu().slots.get(9)));
            context.getInput().setCursorPos((pageSlot[0]+8)*2,(pageSlot[1]+8)*2);
            context.getInput().pressMouse(0);
            context.waitTicks(3);
            context.runOnClient(mc -> require(mc.player.containerMenu.getCarried().getCount()==16,
                    "Native click did not pick up the real PV stack"));
            context.takeScreenshot("storage-live-carried-stack");
            int[] hotbar=context.computeOnClient(mc -> position(mc.player.containerMenu.slots.stream()
                    .filter(s -> s.container==mc.player.getInventory() && s.getContainerSlot()==2).findFirst().orElseThrow()));
            context.getInput().setCursorPos((hotbar[0]+8)*2,(hotbar[1]+8)*2);
            context.getInput().pressMouse(0);
            context.waitTicks(5);
            context.runOnClient(mc -> {
                require(mc.player.getInventory().getItem(2).is(Items.DIAMOND)
                        && mc.player.getInventory().getItem(2).getCount()==16, "PV item did not enter live inventory");
                require(mc.player.containerMenu.getCarried().isEmpty(), "Cursor stack was not placed");
                mc.getToastManager().clear();
            });
            world.getServer().runOnServer(server -> {
                var player=server.getPlayerList().getPlayers().getFirst();
                require(player.getInventory().getItem(2).getCount()==16, "Server did not confirm the inventory transfer");
                require(player.containerMenu.getSlot(9).getItem().isEmpty(), "Server still has the removed PV stack");
            });
            context.takeScreenshot("storage-live-transferred");
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                var gold=screen.getMenu().slots.get(10);
                var right=new MouseButtonEvent(gold.x+8,gold.y+8,new MouseButtonInfo(1,0));
                screen.mouseClicked(right,false); screen.mouseReleased(right);
            });
            context.waitTicks(3);
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                require(screen.getMenu().getCarried().getCount()==4, "Right click did not split the live stack");
                var target=screen.getMenu().slots.stream().filter(s -> s.container==mc.player.getInventory()
                        && s.getContainerSlot()==3).findFirst().orElseThrow();
                var click=new MouseButtonEvent(target.x+8,target.y+8,new MouseButtonInfo(0,0));
                screen.mouseClicked(click,false); screen.mouseReleased(click);
            });
            context.waitTicks(3);
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                var emerald=screen.getMenu().slots.stream().filter(s -> s.container==mc.player.getInventory()
                        && s.getContainerSlot()==9).findFirst().orElseThrow();
                var shift=new MouseButtonEvent(emerald.x+8,emerald.y+8,new MouseButtonInfo(0,1));
                screen.mouseClicked(shift,false); screen.mouseReleased(shift);
            });
            context.waitTicks(4);
            world.getServer().runOnServer(server -> {
                var player=server.getPlayerList().getPlayers().getFirst();
                require(player.getInventory().getItem(3).getCount()==4 && player.containerMenu.getSlot(10).getItem().getCount()==4,
                        "Server did not confirm the split stack");
                require(player.getInventory().getItem(9).isEmpty() && player.containerMenu.getSlot(9).getItem().is(Items.EMERALD),
                        "Shift click did not transfer inventory into the live page");
            });
            int[] emerald=context.computeOnClient(mc -> position(mc.player.containerMenu.getSlot(9)));
            context.getInput().setCursorPos((emerald[0]+8)*2,(emerald[1]+8)*2);
            context.waitTicks(2);
            context.runOnClient(mc -> mc.screen.keyPressed(new KeyEvent(49,0,0)));
            context.waitTicks(3);
            world.getServer().runOnServer(server -> require(server.getPlayerList().getPlayers().getFirst()
                    .getInventory().getItem(0).is(Items.EMERALD), "Hotbar key did not use the remapped live slot"));
            context.takeScreenshot("storage-live-split-shift-hotbar");
            context.getInput().resizeWindow(960,720);
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.waitTicks(3);
            context.runOnClient(mc -> {
                require(LiveStorageView.active(mc.screen), "Small GUI lost the live page");
                mc.getToastManager().clear();
            });
            context.takeScreenshot("storage-live-small-gui");
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                var gold=screen.getMenu().getSlot(10);
                // This pixel used to overlap the preceding cell's vanilla hover padding.
                var click=new MouseButtonEvent(gold.x,gold.y+8,new MouseButtonInfo(0,0));
                screen.mouseClicked(click,false); screen.mouseReleased(click);
            });
            context.waitTicks(3);
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                require(screen.getMenu().getCarried().is(Items.GOLD_INGOT)
                        && screen.getMenu().getCarried().getCount()==4, "Compact adjacent cell selected the wrong native slot");
                var gold=screen.getMenu().getSlot(10);
                var click=new MouseButtonEvent(gold.x+8,gold.y+8,new MouseButtonInfo(0,0));
                screen.mouseClicked(click,false); screen.mouseReleased(click);
            });
            context.waitTicks(3);
            context.runOnClient(mc -> {
                var screen=(AbstractContainerScreen<?>)mc.screen;
                StorageOverview.showVanilla(screen);
                require(!LiveStorageView.active(screen), "Vanilla fallback did not disable custom input");
                require(screen.getMenu().slots.get(9).x==8 && screen.getMenu().slots.get(9).y==36,
                        "Native slot coordinates were not restored");
                mc.player.closeContainer();
            });
            context.waitTicks(3);
            System.out.println("LIVE STORAGE CLIENT CHECKS PASSED: native server-backed menu, active page, carried item, transfer, right split, shift click, hotbar key and restored vanilla geometry");
        }
    }

    /**
     * A click on another page, through a Storage menu that arrives empty and then holds still,
     * so each stage the server really goes through can be looked at. It must never show as itself.
     */
    private static void transit(ClientGameTestContext context, TestSingleplayerContext world) {
        AtomicInteger asked = new AtomicInteger();
        // Here the test server answers within a tick, so looking afterwards proves nothing. This
        // runs straight after the click, the state a real server leaves up for half a second.
        boolean[] glassOnCursor = new boolean[1];
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (StorageOverview.inTransit() && mc.screen instanceof AbstractContainerScreen<?> screen
                    && !screen.getMenu().getCarried().isEmpty()) glassOnCursor[0] = true;
        });
        var root = new SimpleContainer(54);
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getCommands().getDispatcher().register(Commands.literal("storage").executes(command -> {
                command.getSource().getPlayerOrException().openMenu(new SimpleMenuProvider((id, inv, p) ->
                        new ChestMenu(MenuType.GENERIC_9x6, id, inv, root, 6) {
                            @Override public void clicked(int slot, int button, ContainerInput input, Player actor) {
                                asked.set(slot - 8);
                                setCarried(ItemStack.EMPTY);
                                broadcastFullState();
                            }
                        }, Component.literal("Storage")));
                return 1;
            }));
            server.getCommands().sendCommands(player);
            openPage(player, 1);
        });
        context.waitTicks(10);
        // The real mouse: card clicks arrive through the hook that a direct call to the screen skips.
        int[] card = context.computeOnClient(mc -> {
            var l = LiveStorageView.layout((AbstractContainerScreen<?>) mc.screen);
            return new int[]{l.x(2) + 40, l.y(2) + 40};
        });
        context.getInput().setCursorPos(card[0] * 2, card[1] * 2);
        context.getInput().pressMouse(0);
        context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().equals("Storage"), 80);
        context.waitTicks(3);
        context.runOnClient(mc -> require(StorageOverview.inTransit(), "The Storage menu showed as itself on the way to a page"));
        context.takeScreenshot("storage-transit-menu-empty");
        world.getServer().runOnServer(server -> {
            for (int page = 1; page <= 7; page++) {
                ItemStack item = new ItemStack(Items.CHEST);
                item.set(DataComponents.CUSTOM_NAME, Component.literal("Storage Page " + page));
                item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to view!"))));
                root.setItem(8 + page, item);
            }
        });
        context.waitFor(mc -> asked.get() == 2, 80);
        context.waitTicks(3);
        context.runOnClient(mc -> require(!glassOnCursor[0], "The clicked page button rode the cursor on the way to a page"));
        context.takeScreenshot("storage-transit-page-asked-for");
        world.getServer().runOnServer(server -> openPage(server.getPlayerList().getPlayers().getFirst(), 2));
        context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().startsWith("Storage Page"), 80);
        context.waitTicks(3);
        context.runOnClient(mc -> {
            var screen = (AbstractContainerScreen<?>) mc.screen;
            var l = LiveStorageView.layout(screen);
            require(!StorageOverview.inTransit(), "The view kept waiting after the page opened");
            require(screen.getMenu().slots.get(9).x == l.x(2) + 3, "The page did not land on the card that was clicked");
        });
        context.takeScreenshot("storage-transit-arrived");
        clickThrough(context, world, asked);
        context.runOnClient(mc -> mc.player.closeContainer());
        context.waitTicks(3);
        context.runOnClient(mc -> com.endsight.hud.Alerts.slayerQuest());
        context.waitTicks(10);
        context.takeScreenshot("slayer-quest-popup");
        context.runOnClient(mc -> com.endsight.hud.Alert.clear());
        float[] head = new float[1];
        var perspective = com.endsight.visual.Perspective.module();
        context.runOnClient(mc -> {
            head[0] = mc.player.getYRot();
            var view = mc.options.getCameraType();
            perspective.setEnabled().accept(true);
            mc.player.turn(600, 0);
            require(mc.player.getYRot() == head[0], "Free Look turned the player");
            require(com.endsight.visual.Perspective.yaw() != head[0], "Free Look did not turn the camera");
            require(mc.options.getCameraType() != view, "Free Look stayed in first person");
        });
        context.waitTicks(3);
        context.takeScreenshot("perspective-mode");
        context.runOnClient(mc -> {
            perspective.setEnabled().accept(false);
            require(mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON, "Free Look did not put the view back");
            mc.player.turn(600, 0);
            require(mc.player.getYRot() != head[0], "The mouse stayed off the player after Free Look");
            com.endsight.dragons.DropPricesScreen.openCache();
        });
        context.waitTicks(4);
        context.takeScreenshot("drop-prices-cache");
        context.getInput().setCursorPos(600, 190);
        context.getInput().pressMouse(0);
        context.waitTicks(3);
        context.takeScreenshot("drop-prices-editing");
        context.runOnClient(mc -> {
            mc.setScreen(null);
            com.endsight.dragons.DropPricesScreen.openGoop();
        });
        context.waitTicks(4);
        context.takeScreenshot("drop-prices-goop");
        context.runOnClient(mc -> mc.setScreen(null));
        wide(context, world, asked);
    }

    /** A click on a stack in a closed page lands on that stack once the page opens, shift click too. */
    private static void clickThrough(ClientGameTestContext context, TestSingleplayerContext world, AtomicInteger asked) {
        for (boolean shift : new boolean[]{false, true}) {
            world.getServer().runOnServer(server -> openPage(server.getPlayerList().getPlayers().getFirst(), 3));
            context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().startsWith("Storage Page (3/"), 80);
            context.waitTicks(3);
            int[] stack = context.computeOnClient(mc -> {
                var l = LiveStorageView.layout((AbstractContainerScreen<?>) mc.screen);
                return new int[]{l.x(2) + 11, l.y(2) + 26, mc.player.getInventory().countItem(Items.GOLD_INGOT)};
            });
            asked.set(0);
            context.getInput().setCursorPos(stack[0] * 2, stack[1] * 2);
            // The test mouse always reports no modifier keys, so a shift click goes in one step later.
            if (shift) context.runOnClient(mc -> LiveStorageView.click((AbstractContainerScreen<?>) mc.screen, stack[0], stack[1], 0, true));
            else context.getInput().pressMouse(0);
            context.waitFor(mc -> asked.get() == 2, 80);
            world.getServer().runOnServer(server -> openPage(server.getPlayerList().getPlayers().getFirst(), 2));
            context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().startsWith("Storage Page (2/"), 80);
            context.waitTicks(5);
            context.runOnClient(mc -> {
                var menu = ((AbstractContainerScreen<?>) mc.screen).getMenu();
                if (shift) require(menu.slots.get(9).getItem().isEmpty()
                        && mc.player.getInventory().countItem(Items.GOLD_INGOT) == stack[2] + 2, "A shift click on a closed page did not land");
                else require(menu.getCarried().is(Items.GOLD_INGOT) && menu.getCarried().getCount() == 2,
                        "A click on a closed page did not pick up what it was on");
            });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.containerMenu.setCarried(ItemStack.EMPTY);
                player.closeContainer();
            });
            context.waitTicks(3);
        }
    }

    /** At his real window the inventory sits under the pages, and nothing moves from the first screen on. */
    private static void wide(ClientGameTestContext context, TestSingleplayerContext world, AtomicInteger asked) {
        Map<Integer, PageSnapshot> saved = new HashMap<>();
        context.getInput().resizeWindow(1920, 1009);
        context.runOnClient(mc -> {
            mc.options.guiScale().set(2); mc.resizeGui();
            saved.putAll(StoragePreview.snapshots());
            long now = System.currentTimeMillis();
            for (int page = 0; page <= 8; page++) StoragePreview.snapshots().put(page, new PageSnapshot(page, 8, full(page), now));
            mc.getConnection().sendCommand("storage");
        });
        context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().equals("Storage"), 80);
        context.waitTicks(5);
        context.takeScreenshot("storage-menu-wide");
        int[][] menu = context.computeOnClient(mc -> {
            var screen = (AbstractContainerScreen<?>) mc.screen;
            var l = LiveStorageView.layout(screen);
            require(LiveStorageView.active(screen), "The Storage menu is not the storage view");
            require(l.below() && l.pageCapacity() == 10, "His pages do not fit over the inventory");
            return new int[][]{{l.x(2) + 40, l.y(2) + 40}, position(inventorySlot(mc, screen))};
        });
        asked.set(0);
        context.getInput().setCursorPos(menu[0][0] * 2, menu[0][1] * 2);
        context.getInput().pressMouse(0);
        context.waitFor(mc -> asked.get() == 2, 80);
        world.getServer().runOnServer(server -> openPage(server.getPlayerList().getPlayers().getFirst(), 2));
        context.waitFor(mc -> mc.screen != null && mc.screen.getTitle().getString().startsWith("Storage Page"), 80);
        context.waitTicks(5);
        context.takeScreenshot("storage-page-wide");
        context.runOnClient(mc -> {
            var screen = (AbstractContainerScreen<?>) mc.screen;
            var l = LiveStorageView.layout(screen);
            int[] slot = position(inventorySlot(mc, screen));
            require(slot[0] == menu[1][0] && slot[1] == menu[1][1], "The inventory moved when a page opened");
            require(screen.getMenu().slots.get(9).x == l.x(2) + 3, "The page moved when it opened");
            StoragePreview.snapshots().clear();
            StoragePreview.snapshots().putAll(saved);
            mc.player.closeContainer();
        });
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        context.waitTicks(3);
    }

    private static Slot inventorySlot(net.minecraft.client.Minecraft mc, AbstractContainerScreen<?> screen) {
        return screen.getMenu().slots.stream().filter(s -> s.container == mc.player.getInventory() && s.getContainerSlot() == 9)
                .findFirst().orElseThrow();
    }

    private static void openPage(net.minecraft.server.level.ServerPlayer player, int page) {
        var chest = new SimpleContainer(54);
        chest.setItem(9, new ItemStack(Items.GOLD_INGOT, page));
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id, inv, chest), Component.literal("Storage Page (" + page + "/7)")));
    }

    /** What a frame costs at Fear's real window with every card full: printed to compare builds. */
    private static void cost(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<Integer, PageSnapshot> saved = new HashMap<>();
        int[] limit = new int[1];
        boolean[] vsync = new boolean[1];
        context.getInput().resizeWindow(1920, 1009);
        context.runOnClient(mc -> {
            limit[0] = mc.options.framerateLimit().get();
            vsync[0] = mc.options.enableVsync().get();
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            mc.getWindow().updateVsync(false);
            mc.options.guiScale().set(2); mc.resizeGui();
            saved.putAll(StoragePreview.snapshots());
            long now = System.currentTimeMillis();
            for (int page = 0; page <= 7; page++) if (page != 1) StoragePreview.snapshots().put(page, new PageSnapshot(page, 7, full(page), now));
        });
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var chest = new SimpleContainer(54);
            List<ItemStack> items = full(1);
            for (int i = 0; i < 54; i++) chest.setItem(i, items.get(i));
            player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id, inv, chest), Component.literal("Storage Page (1/7)")));
        });
        context.waitTicks(30);
        context.takeScreenshot("storage-full-wall");
        Timer timer = context.computeOnClient(mc -> new Timer(mc.screen));
        context.waitTicks(Integer.getInteger("endsight.storageCostTicks", 400));
        context.runOnClient(mc -> {
            System.out.println("COST storage wall: " + timer.report() + " on " + mc.screen.getClass().getSimpleName());
            StoragePreview.snapshots().clear();
            StoragePreview.snapshots().putAll(saved);
            mc.options.framerateLimit().set(limit[0]);
            mc.options.enableVsync().set(vsync[0]);
            mc.getWindow().updateVsync(vsync[0]);
            mc.player.closeContainer();
        });
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        context.waitTicks(3);
    }

    private static List<ItemStack> full(int seed) {
        var kinds = List.of(Items.DIAMOND_SWORD, Items.AMETHYST_SHARD, Items.PLAYER_HEAD, Items.ENDER_PEARL, Items.LEATHER_CHESTPLATE, Items.GOLD_INGOT);
        var tiers = List.of("LEGENDARY SWORD", "MYTHIC HELMET", "RARE BOOTS", "EPIC ACCESSORY", "UNCOMMON");
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            if (i < 9) { items.add(new ItemStack(Items.GRAY_STAINED_GLASS_PANE)); continue; }
            var kind = kinds.get((i + seed) % kinds.size());
            ItemStack stack = new ItemStack(kind, Math.min(new ItemStack(kind).getMaxStackSize(), 2 + i % 60));
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Worth a lot"), Component.literal(tiers.get(i % tiers.size())))));
            if (i % 5 == 0) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            items.add(stack);
        }
        return items;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** Around the whole of a screen's drawing, the view and anything hooked onto it. */
    private static final class Timer {
        private int frames;
        private long nanos, bytes, began, garbage, opened;
        Timer(Screen screen) {
            ScreenEvents.beforeExtract(screen).register((s, g, mx, my, delta) -> {
                if (opened == 0) opened = System.nanoTime();
                garbage = allocated();
                began = System.nanoTime();
            });
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                nanos += System.nanoTime() - began;
                bytes += allocated() - garbage;
                frames++;
            });
        }
        String report() {
            int counted = Math.max(1, frames);
            long wall = System.nanoTime() - opened;
            return counted + " frames, " + nanos / counted / 1000 + " us to build, " + bytes / counted / 1024 + " KB, "
                    + wall / counted / 1000 + " us a whole frame (" + counted * 1_000_000_000L / Math.max(1, wall) + " fps)";
        }
    }
    private static int[] position(Slot slot) { return new int[]{slot.x,slot.y}; }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
