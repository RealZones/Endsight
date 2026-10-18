package com.endsight.qol;

import com.endsight.storage.Recipes;
import com.endsight.zealots.Zealots;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An item's own rarity and type, shared by outlines, drop colours, MP and pets.
 * The announcement's "EPIC DROP" describes the loot table, not the item's rarity.
 * Server item data survives resource-pack badges; older stacks fall back to lore.
 */
public final class Rarity {
    private Rarity() { }

    public record Info(String rarity, String type) { }

    private static final Pattern LINE = Pattern.compile(
            "\\b(VERY SPECIAL|SPECIAL|DIVINE|MYTHIC|LEGENDARY|EPIC|RARE|UNCOMMON|COMMON)\\b"
                    + "(?:\\s+(?:DUNGEON\\s+)?([A-Z]+))?");
    private static final Map<String, Integer> cache = new HashMap<>();
    private record Seen(CustomData data, ItemLore lore, Info info) { }
    private static final Map<ItemStack, Seen> stacks = new WeakHashMap<>();

    /** The rarity colour, or {@code fallback} if the item has never been seen. */
    public static int colour(String item, int fallback) {
        Integer c = cache.get(item);
        if (c != null && c != 0) return c;
        int found = find(item);
        // A miss can become known when the item is next held or seen in storage.
        if (found != 0) cache.put(item, found);
        return found == 0 ? fallback : found;
    }

    /** This stack's rarity colour; same-name items can have different upgraded rarities. */
    public static int of(ItemStack stack) {
        Info info = read(stack);
        if (info == null) return 0;
        int found = colourOf(info.rarity());
        cache.put(Zealots.strip(stack.getHoverName().getString()).trim(), found);
        return found;
    }

    /** Read components directly: no world, generated tooltip, or tooltip callback is needed. */
    public static Info read(ItemStack stack) {
        if (stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        ItemLore lore = stack.get(DataComponents.LORE);
        Seen seen = stacks.get(stack);
        if (seen != null && seen.data() == data && seen.lore() == lore) return seen.info();
        // copyTag copies all server NBT. Do it only when components change, not for
        // every slot every frame. Weak keys let discarded inventory snapshots go.
        Info info = read(data == null ? new CompoundTag() : data.copyTag(),
                lore == null ? List.of() : lore.lines());
        stacks.put(stack, new Seen(data, lore, info));
        return info;
    }

    static Info read(CompoundTag data, List<Component> lore) {
        CompoundTag item = data.getCompoundOrEmpty("item_data");
        String rarity = canonical(item.getStringOr("rarity", ""));
        String type = item.getStringOr("item_type", "").trim().toUpperCase(Locale.ROOT);
        // Recorded recombobulated items already store the final rarity here.
        // Incrementing it again would turn a Mythic item into Divine.
        if (rarity != null && !type.isEmpty()) return new Info(rarity, type);
        Info footer = footer(lore);
        if (rarity != null) return new Info(rarity, footer == null ? type : footer.type());
        return footer == null ? null : new Info(footer.rarity(), type.isEmpty() ? footer.type() : type);
    }

    private static Info footer(List<Component> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String line = Zealots.strip(lore.get(i).getString());
            StringBuilder plain = new StringBuilder();
            // The saved Void Artifact and Spare Change footers retain their words,
            // but badge backgrounds and spacing glyphs separate rarity from type.
            line.codePoints().forEach(cp -> plain.appendCodePoint(
                    Character.getType(cp) == Character.PRIVATE_USE || Character.isSpaceChar(cp) ? ' ' : cp));
            Matcher m = LINE.matcher(plain.toString().replaceAll("\\s+", " ").trim());
            if (m.find()) return new Info(m.group(1), m.group(2) == null ? "" : m.group(2));
        }
        return null;
    }

    private static String canonical(String word) {
        String clean = word.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        return switch (clean) {
            case "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY SPECIAL" -> clean;
            default -> null;
        };
    }

    /** Sort key: higher is rarer; -1 for unknown. */
    public static int rank(String item) {
        return switch (colour(item, 0)) {
            case 0xFF5555 -> 8;
            case 0x55FFFF -> 7;
            case 0xFF55FF -> 6;
            case 0xFFAA00 -> 5;
            case 0xAA00AA -> 4;
            case 0x5555FF -> 3;
            case 0x55FF55 -> 2;
            case 0xFFFFFF -> 1;
            default -> -1;
        };
    }

    private static int find(String item) {
        ItemStack stack = Recipes.held(item);
        if (stack == null) stack = Recipes.icon(item);
        return stack == null ? 0 : of(stack);
    }

    /** Minecraft's own rarity colours, as the server writes the word. */
    private static int colourOf(String word) {
        return switch (word) {
            case "VERY SPECIAL", "SPECIAL" -> 0xFF5555;
            case "DIVINE" -> 0x55FFFF;
            case "MYTHIC" -> 0xFF55FF;
            case "LEGENDARY" -> 0xFFAA00;
            case "EPIC" -> 0xAA00AA;
            case "RARE" -> 0x5555FF;
            case "UNCOMMON" -> 0x55FF55;
            default -> 0xFFFFFF;
        };
    }
}
