package com.endsight.qol;

import com.endsight.storage.Recipes;
import com.endsight.zealots.Zealots;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An item's own rarity, read off its lore, for colouring its name.
 *
 * The announcement says "EPIC DROP! Golden Eye" in purple, and the alert took its
 * colour from that word - but the word is the drop's class on this server, not the
 * item's rarity, and a Golden Eye painted epic when the item is not reads as wrong to
 * anyone who has held one. The item knows what it is: the last line of its lore is
 * "EPIC HELMET", "LEGENDARY SWORD", "RARE ITEM". So the name is looked up among the
 * items the mod has already seen - the recipe icons ship with it, and your inventory
 * and storage pages add the rest - and the lore decides. An item never seen falls
 * back to the announcement's word, which is still better than grey.
 */
public final class Rarity {

    private Rarity() {
    }

    /**
     * The word can sit anywhere on the line: the server puts its own marks round it -
     * "⚝ MYTHIC SWORD ⟳", "✧ EPIC SWORD V" - and what sits between mark and word is not
     * always a plain space. Upper case only, so a sentence with "rare" in it never matches.
     */
    private static final Pattern LINE = Pattern.compile("\\b(VERY SPECIAL|SPECIAL|DIVINE|MYTHIC|LEGENDARY|EPIC|RARE|UNCOMMON|COMMON)\\b");
    private static final Map<String, Integer> cache = new HashMap<>();

    /** The rarity colour, or {@code fallback} if the item has never been seen. */
    public static int colour(String item, int fallback) {
        Integer c = cache.get(item);
        if (c != null) return c == 0 ? fallback : c;
        int found = find(item);
        // Not found is not remembered: the item may be held, or seen in a chest, a
        // minute from now, and it would have stayed grey until a restart.
        if (found != 0) cache.put(item, found);
        return found == 0 ? fallback : found;
    }

    /**
     * The rarity colour of this very stack, or 0 for an item with no rarity line.
     * Cached by name, so a full window costs one tooltip per new item, not per frame.
     */
    public static int of(ItemStack s) {
        if (s.isEmpty()) return 0;
        String name = s.getHoverName().getString();
        Integer c = cache.get(name);
        if (c != null) return c;
        int found = lore(s);
        cache.put(name, found);
        return found;
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
        ItemStack s = Recipes.icon(item);
        if (s == null) s = Recipes.held(item);
        return s == null ? 0 : lore(s);
    }

    private static int lore(ItemStack s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        List<Component> lines = s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher m = LINE.matcher(Zealots.strip(lines.get(i).getString()).trim());
            if (m.find()) return colourOf(m.group(1));
        }
        return 0;
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
