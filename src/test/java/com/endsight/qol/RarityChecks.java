package com.endsight.qol;

import com.endsight.storage.Recipes;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Recorded resource-pack tooltips, plus component changes that a name cache missed. */
public final class RarityChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.1 binds item defaults during resource loading, after bootstrap. This
        // headless fixture supplies every component it reads and needs no defaults.
        Items.PLAYER_HEAD.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        List<ItemStack> recorded = recordedBadges();
        magicalPower(recorded);
        changingStacks();
        legacyLore();
        System.out.println(checks + " rarity metadata, badge and MP checks passed");
    }

    private static List<ItemStack> recordedBadges() throws Exception {
        // Exact component trees from the saved bag shown in the screenshots. The
        // fixture retains only name, lore, rarity and type; profiles/UUIDs are omitted.
        CompoundTag root;
        try (var input = RarityChecks.class.getResourceAsStream("rarity-badges.nbt")) {
            if (input == null) throw new AssertionError("Missing recorded badge fixture");
            root = NbtIo.readCompressed(input, NbtAccounter.create(64 * 1024));
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (CompoundTag item : root.getListOrEmpty("items").compoundStream().toList()) {
            List<Component> lore = new ArrayList<>();
            for (var line : item.getListOrEmpty("lore"))
                lore.add(ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, line).getOrThrow());
            String name = item.getStringOr("name", "");
            Rarity.Info expected = name.contains("Void Artifact")
                    ? new Rarity.Info("LEGENDARY", "ACCESSORY") : new Rarity.Info("RARE", "ACCESSORY");
            check(Rarity.read(item.getCompoundOrEmpty("data"), lore).equals(expected), "recorded metadata: " + name);
            check(Rarity.read(new CompoundTag(), lore).equals(expected), "recorded badge fallback: " + name);
            ItemStack stack = stack(name, item.getCompoundOrEmpty("data"), lore);
            check(Rarity.of(stack) == (name.contains("Void Artifact") ? 0xFFAA00 : 0x5555FF), "outline colour: " + name);
            stack.remove(DataComponents.LORE);
            check(Rarity.read(stack).equals(expected), "metadata works with no tooltip at all: " + name);
            stacks.add(stack);
        }
        check(stacks.size() == 2, "both screenshot items loaded");
        return stacks;
    }

    private static void magicalPower(List<ItemStack> recorded) throws Exception {
        var field = Recipes.class.getDeclaredField("CHESTS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<ItemStack>> chests = (Map<String, List<ItemStack>>) field.get(null);
        List<ItemStack> bag = new ArrayList<>(recorded);
        // A navigation icon must not earn MP just because it mentions an accessory.
        bag.add(stack("Accessory Bag", new CompoundTag(), List.of(Component.literal("Place a rare accessory here"))));
        chests.put("Accessory Bag #1", bag);
        var dirty = Recipes.class.getDeclaredField("mpDirty");
        dirty.setAccessible(true);
        dirty.setBoolean(null, true);
        var estimate = Recipes.class.getDeclaredMethod("magicalPower");
        estimate.setAccessible(true);
        check((int) estimate.invoke(null) == 24, "Void Artifact 16 MP + Spare Change 8 MP with no readable lore");
        chests.clear();
    }

    private static void changingStacks() {
        ItemStack first = stack("Same name", data("RARE", "ACCESSORY"), List.of());
        ItemStack upgraded = stack("Same name", data("EPIC", "ACCESSORY"), List.of());
        check(Rarity.of(first) == 0x5555FF, "first same-name stack");
        check(Rarity.of(upgraded) == 0xAA00AA, "same-name upgraded stack keeps its own rarity");
        check(Rarity.of(first) == 0x5555FF, "drawing upgraded item does not recolour the other stack");
        Rarity.Info before = Rarity.read(first);
        check(Rarity.read(first) == before, "unchanged components reuse the parsed result");
        CompoundTag recombobulated = data("MYTHIC", "SWORD");
        CompoundTag modifiers = new CompoundTag();
        modifiers.putBoolean("recombobulated", true);
        recombobulated.put("item_modifiers", modifiers);
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(recombobulated));
        check(Rarity.of(first) == 0xFF55FF, "component replacement invalidates cache; recombobulation is already included");
        ItemStack unknown = stack("Same name", new CompoundTag(), List.of());
        check(Rarity.of(unknown) == 0, "unknown stack does not borrow another item's rarity");
        unknown.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§aUNCOMMON ITEM"))));
        check(Rarity.of(unknown) == 0x55FF55, "a cached miss is invalidated when lore arrives");
        check(Rarity.read(stack("Uncoloured", data("LEGENDARY", "PET"), List.of(Component.literal("RARE ITEM"))))
                .equals(new Rarity.Info("LEGENDARY", "PET")), "server metadata wins over stale lore");
    }

    private static void legacyLore() {
        check(Rarity.read(new CompoundTag(), List.of(Component.literal("§9§lRARE ACCESSORY")))
                .equals(new Rarity.Info("RARE", "ACCESSORY")), "plain legacy footer");
        check(Rarity.read(new CompoundTag(), List.of(Component.literal("§d§k0§r §dMYTHIC DUNGEON SWORD §d§k0")))
                .equals(new Rarity.Info("MYTHIC", "SWORD")), "dungeon and recombobulated footer");
        check(Rarity.read(new CompoundTag(), List.of(Component.literal("\uE100VERY\uE200SPECIAL\uE405PET\uE102")))
                .equals(new Rarity.Info("VERY SPECIAL", "PET")), "glyphs within multi-word rarity");
        check(Rarity.read(new CompoundTag(), List.of(Component.literal("An uncommon item with rare abilities"))) == null,
                "description text alone is unknown");
        check(Rarity.read(new CompoundTag(), List.of(Component.literal("\uE100\uE200\uE405"))) == null,
                "unknown glyph-only badge is not guessed from colour");
        check(Rarity.read(data("UNRECOGNIZED", "ACCESSORY"), List.of(Component.literal("RARE ACCESSORY")))
                .equals(new Rarity.Info("RARE", "ACCESSORY")), "unsupported metadata falls back to known lore");
        check(Rarity.read(data("very_special", "pet"), List.of()).equals(new Rarity.Info("VERY SPECIAL", "PET")),
                "canonical metadata spelling");
    }

    private static ItemStack stack(String name, CompoundTag data, List<Component> lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static CompoundTag data(String rarity, String type) {
        CompoundTag item = new CompoundTag();
        item.putString("rarity", rarity);
        item.putString("item_type", type);
        CompoundTag root = new CompoundTag();
        root.put("item_data", item);
        return root;
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
