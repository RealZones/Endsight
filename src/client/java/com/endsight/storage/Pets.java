package com.endsight.storage;

import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What pets do to mining, read off their tooltips as they go past.
 *
 * A pet's lore says it plainly - "Earn 25% more Void Powder from Crying Obsidian" -
 * and the same pet reads the same for everyone, so one sighting of a rarity is
 * enough. Keyed by rarity and name, because an Epic Bal says 15% where a Legendary
 * says 25%. Kept on disk; a pet seen once is known from then on.
 */
public final class Pets {

    private Pets() {
    }

    private static final Pattern RARITY = Pattern.compile("^(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC) PET");
    /** Run over the whole tooltip as one sentence, so it stops at the full stop, not at a line end. */
    private static final Pattern POWDER = Pattern.compile("Earn (\\d+)% more (Void|Ender) Powder from ([^.]+?)\\.");
    private static final Pattern LEVEL = Pattern.compile("^\\[Lvl \\d+\\]\\s*");
    /**
     * "LEGENDARY Bal|Crying Obsidian" -> 25; "*Void" is every Void material.
     *
     * Seeded with what has been read off real tooltips, so a pet does not have to be
     * hovered before its buff counts - nobody hovers a pet they do not own. Legendary
     * Bal 25% and Epic Bal 15% (two tooltips, one of each); Legendary Scatha +20% Void
     * from all sources, its third perk. An Epic Scatha loses that third perk, as the
     * Epic Bal lost Chimney, so it has no powder line; a Rare has one perk, Burrowing.
     * A tooltip that goes past still overrides any of these.
     */
    private static final Map<String, Integer> powder = new HashMap<>(Map.of(
            "LEGENDARY Bal|Crying Obsidian", 25,
            "EPIC Bal|Crying Obsidian", 15,
            "LEGENDARY Scatha|*Void", 20));

    public static void init() {
        load();
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            String name = LEVEL.matcher(Zealots.strip(stack.getHoverName().getString()).trim()).replaceFirst("");
            String rarity = null;
            Map<String, Integer> found = new HashMap<>();
            // One sentence, not lines: "Earn 25% more Void Powder" / "from Crying
            // Obsidian." is wrapped over two, and matched a line at a time it never hit -
            // pets.txt stayed empty and no pet's powder buff was ever applied.
            StringBuilder all = new StringBuilder();
            for (Component c : lines) {
                String line = Zealots.strip(c.getString()).trim();
                all.append(line).append(' ');
                Matcher r = RARITY.matcher(line);
                if (r.find()) rarity = r.group(1);
            }
            Matcher m = POWDER.matcher(all.toString().replaceAll("\\s+", " "));
            while (m.find()) {
                // "from all sources" is every material of that powder: a Scatha's
                // Bejeweled Eyes is +20% Void, on crying obsidian and amethyst alike.
                String where = m.group(3).trim();
                String key = where.equalsIgnoreCase("all sources") ? "*" + m.group(2) : where;
                found.put(key, Integer.parseInt(m.group(1)));
            }
            if (rarity == null || found.isEmpty()) return;
            boolean changed = false;
            for (Map.Entry<String, Integer> e : found.entrySet()) {
                String key = rarity + " " + name + "|" + e.getKey();
                if (!e.getValue().equals(powder.put(key, e.getValue()))) changed = true;
            }
            if (changed) save();
        });
    }

    /**
     * The pet's powder bonus on a material, as a fraction; 0 when it has none or the pet
     * is unknown. A named material wins over an "all sources" line, and they do not stack.
     */
    public static double powderBuff(String petKey, String material, String powderType) {
        if (petKey == null || petKey.isBlank()) return 0;
        Integer named = powder.get(petKey + "|" + material);
        if (named != null) return named / 100.0;
        return powder.getOrDefault(petKey + "|*" + powderType, 0) / 100.0;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("endsight").resolve("pets.txt");
    }

    private static void save() {
        Properties p = new Properties();
        for (Map.Entry<String, Integer> e : powder.entrySet()) p.setProperty("powder." + e.getKey(), String.valueOf(e.getValue()));
        try {
            Files.createDirectories(file().getParent());
            try (var out = Files.newBufferedWriter(file())) {
                p.store(out, "Endsight pets, as their tooltips read");
            }
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(f)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("powder.")) {
                try {
                    powder.put(k.substring(7), Integer.parseInt(p.getProperty(k).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
