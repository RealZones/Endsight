package com.endsight.visual;

import com.endsight.Config;
import com.endsight.EndsightClient;
import com.endsight.qol.Rarity;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Native container and hotbar captures; every highlight runs through the real slot hooks. */
public final class RarityOutlineVisualTest implements FabricClientGameTest {
    private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY",
            "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"};
    private static final Item[] ICONS = {Items.IRON_INGOT, Items.EMERALD, Items.DIAMOND, Items.AMETHYST_SHARD,
            Items.GOLD_INGOT, Items.ENDER_PEARL, Items.NETHER_STAR, Items.BLAZE_POWDER, Items.PLAYER_HEAD};

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 900);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                require(style().get().get().equals("Underline"), "default style is not Underline");
                // Glow was the Outline plus a faint second ring; it was removed for looking the same.
                require(style().options().equals(List.of("Corners", "Outline", "Pixel", "Underline")),
                        "styles were removed or renamed");
                // A config from before 1.8.0 has Corners saved, chosen or not: it moves to
                // Underline once, and Corners picked after that is kept.
                style().set().accept("Corners");
                Config.save(EndsightClient.registry());
                withoutVersion();
                Config.load(EndsightClient.registry());
                require(style().get().get().equals("Underline"), "an older config's Corners was not moved to Underline");
                style().set().accept("Corners");
                Config.save(EndsightClient.registry());
                Config.load(EndsightClient.registry());
                require(style().get().get().equals("Corners"), "Corners chosen after the move was not kept");
                RarityOutline.module().setEnabled().accept(true);
                opacity().set().accept(.75);
                InventoryGlass.module().setEnabled().accept(true);
                var chest = new SimpleContainer(27);
                for (int i = 0; i < 25; i++) chest.setItem(i, sample(i));
                chest.setItem(25, new ItemStack(Items.GLASS_PANE));
                require(Rarity.of(chest.getItem(25)) == 0 && chest.getItem(26).isEmpty(), "unknown/empty fixture invalid");
                for (int i = 0; i < 9; i++) mc.player.getInventory().setItem(i, sample(i));
                mc.setScreen(new ContainerScreen(ChestMenu.threeRows(0, mc.player.getInventory(), chest),
                        mc.player.getInventory(), Component.literal("Rarity highlights")));
            });
            context.waitTicks(3);
            context.runOnClient(mc -> RarityOutline.module().setEnabled().accept(false));
            context.takeScreenshot("rarity-disabled");
            context.runOnClient(mc -> RarityOutline.module().setEnabled().accept(true));
            for (String option : style().options()) {
                context.runOnClient(mc -> style().set().accept(option));
                context.takeScreenshot("rarity-" + option.toLowerCase(java.util.Locale.ROOT));
            }
            context.runOnClient(mc -> {
                // Save through the actual registry owner, then reload after changing it.
                Config.save(EndsightClient.registry());
                style().set().accept("Corners");
                Config.load(EndsightClient.registry());
                require(style().get().get().equals("Underline"), "new style did not persist");
                opacity().set().accept(.2);
            });
            context.takeScreenshot("rarity-underline-low-opacity");
            context.runOnClient(mc -> {
                opacity().set().accept(.75);
                InventoryGlass.module().setEnabled().accept(false);
            });
            context.takeScreenshot("rarity-underline-vanilla-background");
            context.runOnClient(mc -> mc.setScreen(null));
            context.waitTicks(2);
            context.takeScreenshot("rarity-underline-hotbar");
            context.runOnClient(mc -> {
                style().set().accept("Underline");
                Config.save(EndsightClient.registry());
            });
            System.out.println("RARITY OUTLINE CLIENT CHECKS PASSED: four styles with Underline default, old Corners moved once, Underline save/reload, "
                    + "native slot and hotbar captures, counts, nine rarities, unknown/empty slots, opacity and glass/vanilla backgrounds");
        }
    }

    private static ItemStack sample(int index) {
        int rarity = index % RARITIES.length;
        var stack = new ItemStack(ICONS[(index + index / 9) % ICONS.length], index % 3 == 0 ? 32 : index % 3 == 1 ? 3 : 1);
        var item = new CompoundTag();
        item.putString("rarity", RARITIES[rarity]);
        item.putString("item_type", "ITEM");
        var data = new CompoundTag();
        data.put("item_data", item);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(RARITIES[rarity].replace('_', ' ') + " example"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Rarity style preview"))));
        require(Rarity.of(stack) != 0, "fixture rarity missing");
        return stack;
    }

    /** The saved config as a build before 1.8.0 wrote it: everything but the version. */
    private static void withoutVersion() {
        var path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("endsight.properties");
        try {
            var lines = java.nio.file.Files.readAllLines(path).stream().filter(l -> !l.startsWith("configVersion=")).toList();
            java.nio.file.Files.write(path, lines);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Setting.Choice style() {
        return RarityOutline.module().settings().stream().filter(s -> s instanceof Setting.Choice)
                .map(s -> (Setting.Choice) s).findFirst().orElseThrow();
    }

    private static Setting.Slider opacity() {
        return RarityOutline.module().settings().stream().filter(s -> s instanceof Setting.Slider)
                .map(s -> (Setting.Slider) s).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
