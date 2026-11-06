package com.endsight.qol;

import com.endsight.EndsightClient;
import com.endsight.ui.EndsightScreen;
import com.endsight.ui.Keybinds;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An authoritative integrated-server fixture, not a claim about the production server. Heads are
 * Dragsim's real textures. Pets reopen on a click because the live server does; passing while it
 * edited them in place is how a menu that never closed reached a player.
 */
public final class ManualEquipmentBindsVisualTest implements FabricClientGameTest {
    private static final String WARDROBE_KEY = "qol.wardrobe.slot.1", WARDROBE_SWAP = "qol.wardrobe.swap", PET_SWAP = "qol.pets.swap";
    private static final String BADGE = "\ue100\ue200\ue101\ue200\ue101\ue200\ue102\ue200\ue23f%s\ue200\ue101\ue200\ue102\ue200\ue23f%s PET\ue405";
    private static final int PER_PAGE = 5, FIRST_PET = 10;

    private record Pet(String name, int level, String rarity, String kind, String item, String head) { }
    private static final List<Pet> PETS = withCopy(List.of(
            new Pet("Golden Dragon", 200, "LEGENDARY", "COMBAT", "Lucky Clover", "ewogICJ0aW1lc3RhbXAiIDogMTc2ODUyNDcwMjU2MywKICAicHJvZmlsZUlkIiA6ICI4ZTkyZDczNDRhMjE0YTlkYjgzYTVkYjBjOGE1MjEwNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJGYWJsZWRTaGFkb3dGdXJ5IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlOWY5YjFmYzAxNDE2NmNiNDZhMDkzZTUzNDliMmJmNmVkZDIwMWI2ODBkNjJlNDhkYmYzYWY5YjA0NTkxMTYiCiAgICB9CiAgfQp9"),
            new Pet("Warden", 100, "LEGENDARY", "COMBAT", "", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzk1OWFlMDJlMzVjYmM0NzQzZTZlNzAyZmRhNjk4MGJhZDJlOTgwNDk0MDNjMTY5OTYyODZlYjQxMmY5NDY4OSJ9fX0="),
            new Pet("Golem", 100, "LEGENDARY", "COMBAT", "Dwarf Turtle Shelmet", "ewogICJ0aW1lc3RhbXAiIDogMTc4ODU1NTk4MjU2MSwKICAicHJvZmlsZUlkIiA6ICI3NTdmOTBiMjIzNDQ0YjhkOGRhYzgyNDIzMmUyY2VjZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNSEZfR29sZW0iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWM2Y2Q3MjAyYzM0ZTc4ZjMwNzMwOTAzNDlmN2Q5NzNiMjg4YWY1ZTViNzMzNGRkMjQ5MDEwYjNmMjcwNzhmOSIKICAgIH0KICB9Cn0="),
            new Pet("Sheep", 100, "EPIC", "COMBAT", "", "ewogICJ0aW1lc3RhbXAiIDogMTY4NjY4OTIwMjU1NCwKICAicHJvZmlsZUlkIiA6ICIwODFiZTAxZmZlMmU0ODMyODI3MDIwMjBlNmI1M2ExNyIsCiAgInByb2ZpbGVOYW1lIiA6ICJMeXJpY1BsYXRlMjUyNDIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjkyYTFhNWMzMjVmMjVmNzQzOGEwYWJiNGY4NmJhNmNmNzU1NTJkMDJjNzM0OWE3MjkyOTgxNDU5YjMxZDJmNyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"),
            new Pet("Black Cat", 100, "LEGENDARY", "COMBAT", "", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTRiNDVjYmFhMTlmZTNkNjhjODU2Y2QzODQ2YzAzYjVmNTlkZTgxYTQ4MGVlYzkyMWFiNGZhM2NkODEzMTcifX19"),
            new Pet("Bal", 100, "LEGENDARY", "MINING", "", "ewogICJ0aW1lc3RhbXAiIDogMTYyMDM3NTcwNjg0MywKICAicHJvZmlsZUlkIiA6ICIwNWQ0NTNiZWE0N2Y0MThiOWI2ZDUzODg0MWQxMDY2MCIsCiAgInByb2ZpbGVOYW1lIiA6ICJFY2hvcnJhIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M0NjliYTIwNDcxMjJlMGEyZGUzYzc0MzdhZDNkZDVkMzFmMWFjMmQyN2FiZGU5Zjg4NDFlMWQ5MmE4YzViNzUiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="),
            new Pet("Scatha", 100, "LEGENDARY", "MINING", "Combat XP Boost", "ewogICJ0aW1lc3RhbXAiIDogMTYyMDQ0NTc2NDQ1MSwKICAicHJvZmlsZUlkIiA6ICJmNDY0NTcxNDNkMTU0ZmEwOTkxNjBlNGJmNzI3ZGNiOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJSZWxhcGFnbzA1IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2RmMDNhZDk2MDkyZjNmNzg5OTAyNDM2NzA5Y2RmNjlkZTZiNzI3YzEyMWIzYzJkYWVmOWZmYTFjY2FlZDE4NmMiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="),
            new Pet("Goop", 87, "MYTHIC", "COMBAT", "", "eyJ0ZXh0dXJlcyI6IHsiU0tJTiI6IHsidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODk1YWVlYzZiODQyYWRhODY2OWY4NDZkNjViYzQ5NzYyNTk3ODI0YWI5NDRmMjJmNDViZjNiYmI5NDFhYmU2YyJ9fX0=")
    ));
    /** Index of the second Scatha, on the same page as the first. The two make one tile. */
    private static final int COPY = 8, TILES = PETS.size() - 1;
    private static List<Pet> withCopy(List<Pet> pets) {
        List<Pet> all = new ArrayList<>(pets);
        all.add(pets.get(6));
        return List.copyOf(all);
    }

    /** What the fake server holds. */
    private static final class Server {
        final AtomicInteger outfit = new AtomicInteger(), wardrobeClicks = new AtomicInteger();
        final AtomicInteger pet = new AtomicInteger(-1), petClicks = new AtomicInteger(), petOpens = new AtomicInteger();
        /** Where /pets opens, and whether its title says which page it is: neither seen live with more than one page. */
        final AtomicInteger petsOpenOn = new AtomicInteger(1);
        final AtomicBoolean numbered = new AtomicBoolean(true);
        final AtomicLong elapsed = new AtomicLong();
        final AtomicBoolean early = new AtomicBoolean(), wardrobeReopens = new AtomicBoolean(), strayClick = new AtomicBoolean();
    }

    @Override public void runTest(ClientGameTestContext context) {
        Map<String, Integer> keys = context.computeOnClient(mc -> Keybinds.all());
        Server server = new Server();
        Supplier<String> state = () -> "wardrobeClicks=" + server.wardrobeClicks.get() + ", outfit=" + server.outfit.get()
                + ", petOpens=" + server.petOpens.get() + ", petClicks=" + server.petClicks.get() + ", pet=" + server.pet.get();
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2); mc.resizeGui();
                require(EndsightClient.registry().all().stream().anyMatch(m -> m.id().equals("qol.wardrobe")), "Wardrobe module is not integrated");
                require(EndsightClient.registry().all().stream().anyMatch(m -> m.id().equals("qol.pets")), "Pet module is not integrated");
                for (String id : Keybinds.all().keySet()) Keybinds.clear(id);
                Keybinds.set(WARDROBE_KEY, GLFW.GLFW_KEY_F6);
                mc.setScreen(null);
            });
            world.getServer().runOnServer(host -> {
                var dispatcher = host.getCommands().getDispatcher();
                dispatcher.register(Commands.literal("wardrobe").executes(command -> {
                    openWardrobe(command.getSource().getPlayerOrException(), server);
                    return 1;
                }));
                dispatcher.register(Commands.literal("pets").executes(command -> {
                    server.petOpens.incrementAndGet();
                    openPets(command.getSource().getPlayerOrException(), server, server.petsOpenOn.get());
                    return 1;
                }));
                host.getCommands().sendCommands(host.getPlayerList().getPlayers().getFirst());
            });
            context.waitTicks(3);

            // The wardrobe key: the server's opening delay, one click, then already-on is a no-op.
            pressPolledKey(context, GLFW.GLFW_KEY_F6);
            context.waitFor(mc -> mc.screen != null, 80);
            context.takeScreenshot("manual-wardrobe-server-menu-before-swap");
            context.waitFor(mc -> server.wardrobeClicks.get() == 1 && mc.screen == null, 180);
            require(server.outfit.get() == 1, "Wardrobe key did not equip the server outfit");
            require(!server.early.get() && server.elapsed.get() >= 1_000, "Wardrobe clicked before the server opening delay");
            pressPolledKey(context, GLFW.GLFW_KEY_F6);
            context.waitFor(mc -> mc.screen != null, 80);
            context.waitFor(mc -> mc.screen == null, 180);
            require(server.wardrobeClicks.get() == 1 && server.outfit.get() == 1, "Already equipped wardrobe bind toggled the outfit off");
            context.runOnClient(mc -> require(said(mc, "Slot 1 is already on."), "A key with nothing to do said nothing"));

            // The sidebar opens straight on Pets, empty until Load walks the server's pages.
            context.runOnClient(mc -> mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry())));
            context.waitTicks(2);
            context.runOnClient(ManualEquipmentBindsVisualTest::clickLoadouts);
            context.waitTicks(4);
            context.takeScreenshot("loadouts-empty");
            context.runOnClient(mc -> loadout("qol.pets").load().run());
            await(context, "load returns to our page", mc -> mc.screen instanceof EndsightScreen && loadout("qol.pets").tiles().get().size() == TILES, 200, state);
            context.waitTicks(30);   // head textures arrive over the network
            context.takeScreenshot("loadouts-pets");
            // Opening on the last page, and with no numbers in the title, must still find them all.
            server.petsOpenOn.set(2);
            server.numbered.set(false);
            context.runOnClient(mc -> loadout("qol.pets").load().run());
            context.waitFor(mc -> !(mc.screen instanceof EndsightScreen), 40);
            await(context, "refresh from the last page", mc -> mc.screen instanceof EndsightScreen, 200, state);
            server.petsOpenOn.set(1);
            server.numbered.set(true);
            require(context.computeOnClient(mc -> loadout("qol.pets").tiles().get().size()) == TILES, "Refresh lost the pets before the page it opened on");
            // The server tidying up after its menu, which vanilla answers by shutting any screen.
            context.runOnClient(mc -> mc.getConnection().handleContainerClose(new ClientboundContainerClosePacket(0)));
            context.waitTicks(2);
            context.runOnClient(mc -> require(mc.screen instanceof EndsightScreen, "A close for no menu shut our page"));

            // Choosing is two clicks: the side, then the pet; the second side arms itself.
            context.runOnClient(mc -> click(mc, side(mc, 0)));
            context.waitTicks(3);
            context.takeScreenshot("loadouts-choosing");
            context.runOnClient(mc -> click(mc, tile(mc, 0)));
            context.runOnClient(mc -> click(mc, tile(mc, 6)));
            context.waitTicks(3);
            context.takeScreenshot("loadouts-swap-needs-key");
            context.runOnClient(mc -> {
                var chosen = loadout("qol.pets").swap().chosen().get();
                var tiles = loadout("qol.pets").tiles().get();
                require(chosen.get(0).equals(tiles.get(0).id()) && chosen.get(1).equals(tiles.get(6).id()), "Swap sides were not chosen: " + chosen);
                // Binding from the chips, by the same clicks and key a player uses.
                click(mc, swapChip(mc));
                mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_R, 0, 0));
                click(mc, tile(mc, 1));
                mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_F7, 0, 0));
                require(Keybinds.get(PET_SWAP) == GLFW.GLFW_KEY_R, "Swap chip did not bind");
                require(Keybinds.get(tiles.get(1).id()) == GLFW.GLFW_KEY_F7, "Tile did not bind");
            });
            context.waitTicks(4);
            context.takeScreenshot("loadouts-pets-bound");

            // One pet's key, then the already-out no-op.
            context.runOnClient(mc -> mc.setScreen(null));
            context.waitTicks(3);
            pressPolledKey(context, GLFW.GLFW_KEY_F7);
            await(context, "pet key summons", mc -> server.pet.get() == 1 && mc.screen == null, 180, state);
            int opens = server.petOpens.get();
            pressPolledKey(context, GLFW.GLFW_KEY_F7);
            await(context, "second press reached the server", mc -> server.petOpens.get() == opens + 1, 80, state);
            await(context, "second press closed the menu", mc -> mc.screen == null, 180, state);
            require(server.petClicks.get() == 1 && server.pet.get() == 1, "Already active pet bind dismissed the pet");
            // Learning mid-click once read the clicked slot as empty and lost that pet from the list.
            require(context.computeOnClient(mc -> loadout("qol.pets").tiles().get().size()) == TILES, "A clicked pet fell off the list");

            // The swap key: first pet, then the second on page two, then back.
            pressPolledKey(context, GLFW.GLFW_KEY_R);
            await(context, "swap to first", mc -> server.pet.get() == 0 && mc.screen == null, 180, state);
            context.waitTicks(10);
            // A page away with nothing in the title to say where the arrows have got to.
            server.numbered.set(false);
            pressPolledKey(context, GLFW.GLFW_KEY_R);
            await(context, "swap to second, a page away", mc -> server.pet.get() == 6 && mc.screen == null, 240, state);
            server.numbered.set(true);
            context.waitTicks(10);
            pressPolledKey(context, GLFW.GLFW_KEY_R);
            await(context, "swap back", mc -> server.pet.get() == 0 && mc.screen == null, 240, state);
            context.waitTicks(10);
            // Pet changed by hand behind the mod's back: the menu is believed over the memory.
            server.pet.set(6);
            pressPolledKey(context, GLFW.GLFW_KEY_R);
            await(context, "swap corrects a stale memory", mc -> server.pet.get() == 0 && mc.screen == null, 240, state);
            context.waitTicks(10);
            require(context.computeOnClient(mc -> loadout("qol.pets").tiles().get().size()) == TILES, "Swapping lost a pet from the list");
            require(context.computeOnClient(mc -> loadout("qol.pets").tiles().get().get(0).current()), "The swapped-in pet is not marked active");

            // Two identical Scathas on one page: the key works, and with either out there is nothing to do.
            context.runOnClient(mc -> {
                var scatha = loadout("qol.pets").tiles().get().get(6);
                require(scatha.detail().contains("2 copies"), "Copies did not become one tile: " + scatha.detail());
                Keybinds.set(scatha.id(), GLFW.GLFW_KEY_F8);
            });
            pressPolledKey(context, GLFW.GLFW_KEY_F8);
            await(context, "a copy comes out", mc -> server.pet.get() == 6 && mc.screen == null, 240, state);
            context.waitTicks(10);
            server.pet.set(COPY);
            int petClicks = server.petClicks.get();
            pressPolledKey(context, GLFW.GLFW_KEY_F8);
            await(context, "the other copy is already out", mc -> said(mc, "Scatha is already out.") && mc.screen == null, 240, state);
            require(server.pet.get() == COPY && server.petClicks.get() == petClicks, "A key on copies swapped one for the other");
            context.waitTicks(10);

            // The small + on the swap caption, a click on a real page: one more swap with its own key.
            context.runOnClient(mc -> openPets(mc, ""));
            context.waitTicks(4);
            context.runOnClient(mc -> {
                click(mc, addSwap(mc));
                require(loadout("qol.pets").swaps().get().size() == 2, "The + did not add a swap");
                var more = loadout("qol.pets").swaps().get().get(1);
                var tiles = loadout("qol.pets").tiles().get();
                more.choose().accept(0, tiles.get(1).id());
                more.choose().accept(1, tiles.get(2).id());
                Keybinds.set(more.key(), GLFW.GLFW_KEY_H);
            });
            context.waitTicks(6);
            context.takeScreenshot("loadouts-two-swaps");
            context.runOnClient(mc -> mc.setScreen(null));
            context.waitTicks(3);
            pressPolledKey(context, GLFW.GLFW_KEY_H);
            await(context, "second swap", mc -> server.pet.get() == 1 && mc.screen == null, 240, state);
            context.waitTicks(10);
            pressPolledKey(context, GLFW.GLFW_KEY_H);
            await(context, "second swap back", mc -> server.pet.get() == 2 && mc.screen == null, 240, state);
            context.waitTicks(10);
            pressPolledKey(context, GLFW.GLFW_KEY_R);
            await(context, "the first swap still works", mc -> server.pet.get() == 0 && mc.screen == null, 240, state);
            context.waitTicks(10);
            // Taken away, its key goes with it, and a save read back has what was left.
            context.runOnClient(mc -> {
                var more = loadout("qol.pets").swaps().get().get(1);
                String key = more.key();
                more.remove().run();
                require(loadout("qol.pets").swaps().get().size() == 1, "The swap was not removed");
                require(Keybinds.get(key) == Keybinds.NONE, "A removed swap kept its key");
            });

            // Same key idea for outfits, and the wardrobe page itself.
            context.runOnClient(mc -> {
                var swap = loadout("qol.wardrobe").swap();
                var tiles = loadout("qol.wardrobe").tiles().get();
                swap.choose().accept(0, tiles.get(0).id());
                swap.choose().accept(1, tiles.get(1).id());
                Keybinds.set(WARDROBE_SWAP, GLFW.GLFW_KEY_G);
            });
            pressPolledKey(context, GLFW.GLFW_KEY_G);
            // Walking on through the wait, pressing the swap again and a stray click in the
            // menu, as in a fight: none of them may stop it.
            context.waitFor(mc -> mc.screen != null, 80);
            context.getInput().holdKeyFor(GLFW.GLFW_KEY_W, 2);
            context.waitTicks(2);
            context.getInput().holdKeyFor(GLFW.GLFW_KEY_G, 2);
            context.waitTicks(2);
            server.strayClick.set(true);
            context.getInput().setCursorPos(640, 300);
            context.getInput().pressMouse(0);
            context.waitTicks(2);
            await(context, "wardrobe swap on", mc -> server.outfit.get() == 2 && mc.screen == null, 200, state);
            context.waitTicks(4);
            // Nobody has seen what the live wardrobe does after a click, so both have to end the same.
            server.wardrobeReopens.set(true);
            pressPolledKey(context, GLFW.GLFW_KEY_G);
            await(context, "wardrobe swap back", mc -> server.outfit.get() == 1 && mc.screen == null, 200, state);
            // Nothing pressed Refresh on this page: opening the menu has to be enough on its own.
            context.runOnClient(mc -> {
                var tiles = loadout("qol.wardrobe").tiles().get();
                require(tiles.get(0).icons().stream().anyMatch(stack -> !stack.isEmpty()), "Outfit pictures were not learned from the open menu");
                require(tiles.get(0).current(), "The equipped outfit was not recognised");
            });

            context.runOnClient(mc -> {
                mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
                try {
                    var field = EndsightScreen.class.getDeclaredField("expandedId"); field.setAccessible(true);
                    field.set(null, "qol.wardrobe");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            context.waitTicks(6);
            context.takeScreenshot("loadouts-wardrobe");
            // A big GUI scale in a small window: tiles drop to one column rather than crush the text.
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.waitTicks(6);
            context.takeScreenshot("loadouts-narrow");
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            context.waitTicks(2);

            // The swap bar's key from our own page hands the screen back and the outfit still flips.
            context.runOnClient(mc -> mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry())));
            context.runOnClient(mc -> WardrobeBinds.request(1));
            // The menu, not just "no screen": the request clears ours first, and leaving here
            // early let the wardrobe open over whatever came next.
            context.waitFor(mc -> mc.screen instanceof AbstractContainerScreen<?>, 80);
            context.waitFor(mc -> mc.screen == null, 180);
            require(server.wardrobeClicks.get() == 3, "Own-UI wardrobe no-op changed the outfit");
            manyPets(context);
            System.out.println("MANUAL EQUIPMENT CLIENT CHECKS PASSED: sidebar entry, refresh across pages and back to our page, swap sides chosen and bound by click, single pet key, swap across pages, stale memory corrected from the menu, wardrobe swap, delay and no-ops");
        } finally {
            context.runOnClient(mc -> {
                for (String id : Keybinds.all().keySet()) Keybinds.clear(id);
                keys.forEach(Keybinds::set);
                MenuBindRequest.cancel("Wardrobe Binds"); MenuBindRequest.cancel("Pet Binds");
            });
        }
    }

    /** A collection far past anything real data here can show: what it costs, at his real window. */
    private static void manyPets(ClientGameTestContext context) {
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
            seedPets(300);
            openPets(mc, "");
        });
        context.waitTicks(40);
        context.takeScreenshot("loadouts-300-pets");
        context.runOnClient(mc -> openPets(mc, "black cat 1"));
        context.waitTicks(20);
        context.takeScreenshot("loadouts-300-pets-searched");
        context.runOnClient(mc -> openPets(mc, ""));
        context.waitTicks(20);
        context.runOnClient(mc -> require(mc.screen instanceof EndsightScreen, "Timing the wrong screen: " + mc.screen));
        Timer timer = context.computeOnClient(mc -> new Timer(mc.screen));
        context.waitTicks(200);
        context.runOnClient(mc -> System.out.println("COST 300 pets: " + timer.report()));
        context.runOnClient(mc -> {
            mc.options.framerateLimit().set(limit[0]);
            mc.options.enableVsync().set(vsync[0]);
            mc.getWindow().updateVsync(vsync[0]);
            seedPets(0);
            mc.setScreen(null);
        });
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        context.waitTicks(3);
    }

    @SuppressWarnings("unchecked")
    private static void seedPets(int count) {
        try {
            var field = PetBinds.class.getDeclaredField("pages");
            field.setAccessible(true);
            var pages = (Map<Integer, List<PetBinds.Learned>>) field.get(null);
            pages.clear();
            for (int i = 0; i < count; i++) {
                Pet look = PETS.get(i % PETS.size());
                int page = i / 45 + 1;
                pages.computeIfAbsent(page, ignored -> new ArrayList<>()).add(new PetBinds.Learned(look.name + " " + i, look.rarity,
                        i % 3 == 0 ? "Lucky Clover" : "", 100, page, head(look.head)));
            }
            var rebuild = PetBinds.class.getDeclaredMethod("rebuild");
            rebuild.setAccessible(true);
            rebuild.invoke(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void openPets(Minecraft mc, String query) {
        try {
            for (var value : Map.of("category", (Object) "Loadouts", "expandedId", "qol.pets", "query", query, "scroll", 0).entrySet()) {
                var field = EndsightScreen.class.getDeclaredField(value.getKey());
                field.setAccessible(true);
                field.set(null, value.getValue());
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** Around the whole of a screen's drawing, and the wall clock for what the frame really cost. */
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

    private static Setting.Loadout loadout(String module) {
        return EndsightClient.registry().all().stream().filter(m -> m.id().equals(module)).flatMap(m -> m.settings().stream())
                .filter(Setting.Loadout.class::isInstance).map(Setting.Loadout.class::cast).findFirst().orElseThrow();
    }

    // Where things are on screen, from the same numbers the screens use.
    private static int sidebar(Minecraft mc) { return Math.min(156, Math.max(100, Theme.panelW(mc.screen.width) / 4)); }
    private static int bodyX(Minecraft mc) { return Theme.panelX(mc.screen.width) + sidebar(mc) + 12 + 4 + 12; }
    private static int bodyY(Minecraft mc) { return Theme.panelY(mc.screen.height) + 66 + 8 + 42 + 3; }
    private static int bodyW(Minecraft mc) { return Theme.panelW(mc.screen.width) - sidebar(mc) - 24 - 8 - 24; }
    private static int[] side(Minecraft mc, int side) { return new int[]{bodyX(mc) + 20 + side * ((bodyW(mc) - 70) / 2 + 22), bodyY(mc) + 14 + 18}; }
    private static int[] addSwap(Minecraft mc) { return new int[]{bodyX(mc) + bodyW(mc) - 6, bodyY(mc) + 4}; }
    private static int[] swapChip(Minecraft mc) { return new int[]{bodyX(mc) + bodyW(mc) - 20, bodyY(mc) + 14 + 18}; }
    private static int[] tile(Minecraft mc, int index) {
        int tileW = (bodyW(mc) - 4) / 2;
        return new int[]{bodyX(mc) + index % 2 * (tileW + 4) + 40, bodyY(mc) + 14 + 36 + 12 + 14 + index / 2 * 40 + 18};
    }
    private static void click(Minecraft mc, int[] at) {
        mc.screen.mouseClicked(new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(0, 0)), false);
        mc.screen.mouseReleased(new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(0, 0)));
    }
    private static void clickLoadouts(Minecraft mc) {
        int index = EndsightClient.registry().categories().indexOf("Loadouts");
        require(index >= 0, "Loadouts is not in the sidebar");
        // The tight sidebar: this window is short enough for the keybinds footer to close it up.
        click(mc, new int[]{Theme.panelX(mc.screen.width) + 30, Theme.panelY(mc.screen.height) + 33 + 4 + 23 * (index + 2) + 10});
        require(mc.screen instanceof EndsightScreen, "Sidebar click left the browser");
    }

    private static void await(ClientGameTestContext context, String phase, Predicate<Minecraft> condition,
                              int ticks, Supplier<String> state) {
        try { context.waitFor(condition, ticks); }
        catch (AssertionError failure) {
            context.runOnClient(mc -> {
                System.out.println("MANUAL EQUIPMENT FAILURE: " + phase + "; " + state.get() + "; screen="
                        + (mc.screen == null ? "none" : mc.screen.getClass().getName() + ":" + mc.screen.getTitle().getString()));
                System.out.println("MANUAL EQUIPMENT PICKS: " + loadout("qol.pets").swap().chosen().get());
                for (var tile : loadout("qol.pets").tiles().get()) System.out.println("MANUAL EQUIPMENT TILE: " + tile.id() + " / " + tile.detail());
                for (var line : mc.gui.getChat().allMessages)
                    System.out.println("MANUAL EQUIPMENT CLIENT CHAT: " + line.content().getString());
            });
            throw new AssertionError(phase, failure);
        }
    }

    private static boolean said(Minecraft mc, String text) {
        return mc.gui.getChat().allMessages.stream().anyMatch(line -> line.content().getString().contains(text));
    }

    private static void pressPolledKey(ClientGameTestContext context, int key) {
        // TestInput.pressKey releases before its first tick. Production action
        // binds poll held state, so keep one press down across two real ticks.
        context.getInput().holdKeyFor(key, 2);
        context.waitTicks(2);
    }

    private static void openWardrobe(ServerPlayer player, Server server) {
        long opened = System.currentTimeMillis();
        SimpleContainer contents = new SimpleContainer(54);
        fillWardrobe(contents, server.outfit.get());
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new ChestMenu(MenuType.GENERIC_9x6, id, inventory, contents, 6) {
                    @Override public void clicked(int slot, int button, ContainerInput input, Player actor) {
                        if (server.strayClick.getAndSet(false)) {
                            setCarried(ItemStack.EMPTY);
                            broadcastFullState();
                            return;
                        }
                        require(slot >= 36 && slot <= 38 && button == 0 && input == ContainerInput.PICKUP,
                                "Bind clicked a non-selector or used a different action");
                        require(server.outfit.get() != slot - 35, "Bind clicked the outfit already on");
                        long age = System.currentTimeMillis() - opened;
                        server.elapsed.set(age);
                        if (age < 1_000) server.early.set(true);
                        server.wardrobeClicks.incrementAndGet();
                        server.outfit.set(slot - 35);
                        setCarried(ItemStack.EMPTY);
                        if (server.wardrobeReopens.get()) {
                            ((ServerPlayer) actor).level().getServer().execute(() -> openWardrobe((ServerPlayer) actor, server));
                            return;
                        }
                        fillWardrobe(contents, server.outfit.get());
                        broadcastFullState();
                    }
                }, Component.literal("Wardrobe")));
    }

    private static void fillWardrobe(SimpleContainer contents, int equipped) {
        for (int i = 0; i < contents.getContainerSize(); i++) contents.setItem(i, new ItemStack(Items.GRAY_STAINED_GLASS_PANE));
        List<List<ItemStack>> sets = List.of(
                List.of(head(PETS.get(0).head), new ItemStack(Items.NETHERITE_CHESTPLATE), new ItemStack(Items.NETHERITE_LEGGINGS), new ItemStack(Items.NETHERITE_BOOTS)),
                List.of(new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS)),
                List.of(new ItemStack(Items.GOLDEN_HELMET), new ItemStack(Items.GOLDEN_CHESTPLATE), new ItemStack(Items.GOLDEN_LEGGINGS), new ItemStack(Items.GOLDEN_BOOTS)));
        for (int outfit = 1; outfit <= 9; outfit++) {
            if (outfit <= sets.size())
                for (int row = 0; row < 4; row++) contents.setItem(outfit - 1 + 9 * row, sets.get(outfit - 1).get(row));
            String line = outfit > 5 ? "This slot is locked!" : outfit > sets.size() ? "This slot is empty!"
                    : outfit == equipped ? "Click to unequip!" : "Click to equip!";
            ItemStack selector = new ItemStack(outfit == equipped ? Items.LIME_DYE : outfit > sets.size() ? Items.GRAY_DYE : Items.PINK_DYE);
            selector.set(DataComponents.CUSTOM_NAME, Component.literal("Slot " + outfit));
            selector.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(line))));
            contents.setItem(35 + outfit, selector);
        }
    }

    private static void openPets(ServerPlayer player, Server server, int page) {
        SimpleContainer contents = new SimpleContainer(54);
        int pages = (PETS.size() + PER_PAGE - 1) / PER_PAGE;
        fillPets(contents, server.pet.get(), page, pages);
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new ChestMenu(MenuType.GENERIC_9x6, id, inventory, contents, 6) {
                    @Override public void clicked(int slot, int button, ContainerInput input, Player actor) {
                        require(button == 0 && input == ContainerInput.PICKUP, "Bind used a different action");
                        setCarried(ItemStack.EMPTY);
                        if (slot == 53 || slot == 45) {
                            require(slot == 53 ? page < pages : page > 1, "Page arrow clicked past the end");
                            int next = slot == 53 ? page + 1 : page - 1;
                            ((ServerPlayer) actor).level().getServer().execute(() -> openPets((ServerPlayer) actor, server, next));
                            return;
                        }
                        int pet = slot - FIRST_PET + (page - 1) * PER_PAGE;
                        require(slot >= FIRST_PET && pet < PETS.size() && (slot - FIRST_PET) < PER_PAGE, "Bind clicked a non-pet slot " + slot);
                        require(server.pet.get() != pet, "Bind dismissed the pet that was out");
                        server.petClicks.incrementAndGet();
                        server.pet.set(pet);
                        ((ServerPlayer) actor).level().getServer().execute(() -> openPets((ServerPlayer) actor, server, page));
                    }
                }, Component.literal(server.numbered.get() ? "Pets (" + page + "/" + pages + ")" : "Pets")));
    }

    private static void fillPets(SimpleContainer contents, int active, int page, int pages) {
        for (int i = 0; i < contents.getContainerSize(); i++) contents.setItem(i, new ItemStack(Items.BLACK_STAINED_GLASS_PANE));
        for (int i = 0; i < PER_PAGE; i++) {
            int index = (page - 1) * PER_PAGE + i;
            if (index < PETS.size()) contents.setItem(FIRST_PET + i, pet(PETS.get(index), index == active));
        }
        if (page < pages) contents.setItem(53, arrow("Next Page"));
        if (page > 1) contents.setItem(45, arrow("Previous Page"));
    }

    private static ItemStack arrow(String name) {
        ItemStack stack = new ItemStack(Items.ARROW);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static ItemStack head(String texture) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "Endsight", properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        return stack;
    }

    private static ItemStack pet(Pet pet, boolean active) {
        ItemStack stack = head(pet.head);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("[Lvl " + pet.level + "] " + pet.name).withColor(0xFFAA00));
        List<Component> lore = new ArrayList<>();
        if (!pet.item.isEmpty()) { lore.add(Component.literal("Held Item")); lore.add(Component.literal(pet.item)); }
        lore.add(Component.literal(active ? "Click to deactivate!" : "Click to summon!"));
        lore.add(Component.literal(String.format(BADGE, pet.rarity, pet.kind)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
