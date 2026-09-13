package com.endsight.qol;

import com.endsight.dragons.DragonTimer;
import com.endsight.zealots.Zealots;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a drop out of a chat line and says how much it matters, for the tracker and
 * the copier to share.
 *
 * <h2>Tiers are yours, not the game's</h2>
 * The game colours Aspect of the Dragons gold and drops it one kill in fourteen. Nobody
 * wants a line for that. So the tier of a drop comes from a list you keep - shipped
 * with a default built from the bestiary, copied to {@code config/endsight/drops.txt}
 * on first run, and read from there after - set by rarity and value together, the way
 * the old 1.8.9 alerter's categories were. New items get a line in that file, not a
 * new build.
 *
 * <h2>The two shapes a drop takes in chat</h2>
 * The server's announcement - "RARE DROP! (Summoning Eye) (✯ 239%)", "EPIC DROP!
 * Golden Eye" - and, with /debug on, the kill line "loot number: 7 → Golden Eye". An
 * item not in your list falls back to the announcement's tier word, or on a /debug line
 * to the item's colour, so a brand new item still lands somewhere sensible.
 */
public final class Drops {

    private Drops() {
    }

    public static final List<String> TIERS = List.of("Any drop", "Rare and up", "Epic and up", "Legendary and up");

    public record Drop(String item, int tier) {
    }

    private record Rule(String needle, int tier) {
    }

    /** Anchored: only tier words may come before DROP!, so a speaker in front fails it. */
    private static final Pattern ANNOUNCE = Pattern.compile("^([A-Z][A-Z ]*?)\\s*DROP!\\s*\\(?([^()]+?)\\)?\\s*(\\(.*)?$");
    private static final Pattern LOOT = Pattern.compile("loot number:[^→]*→\\s*(?:§[k-or])*(§[0-9a-f])?(.+)$");

    /** Longest needle first, so "Golden Hot Potato Book" wins over "Hot Potato Book". */
    private static List<Rule> rules;

    /** The same item inside this window is the same drop reported twice. */
    private static final long REPEAT_MS = 1_500;
    private static String lastItem = "";
    private static long lastAt;

    /**
     * The drop in this line, or null if it is not one - or if it is the one just seen.
     * {@code raw} keeps its colour codes.
     *
     * With /debug on, one drop is two lines a tick apart: "loot number: 57 → Golden
     * Eye" and then "EPIC DROP! Golden Eye". Counting both put every drop on the tracker
     * twice and rang the alert twice. The second sighting of an item within a second and
     * a half is the same drop.
     *
     * The /debug line only counts for an item in your list. Its colour is not the
     * rarity - the log shows a §a Hot Potato Book on the loot line and a §5 one on the
     * announcement - so for anything unlisted the announcement, with its tier word, is
     * the one to trust, and it always follows.
     */
    public static Drop parse(String raw) {
        String line = Zealots.strip(raw).trim();
        // Someone pasting their drop into chat is "[MVP+] Name: EPIC DROP! Golden Eye",
        // which is not your drop. The server's own line has no speaker in front of it.
        if (DragonTimer.isPlayerChat(line)) return null;
        Matcher a = ANNOUNCE.matcher(line);
        if (a.find()) {
            String item = a.group(2).trim();
            return once(new Drop(item, tierOf(item, rank(a.group(1)))));
        }
        Matcher l = LOOT.matcher(raw);
        if (l.find()) {
            String item = Zealots.strip(l.group(2)).trim();
            int tier = tierOf(item, -1);
            return tier < 0 ? null : once(new Drop(item, tier));
        }
        return null;
    }

    private static Drop once(Drop d) {
        long now = System.currentTimeMillis();
        if (d.item().equalsIgnoreCase(lastItem) && now - lastAt < REPEAT_MS) return null;
        lastItem = d.item();
        lastAt = now;
        return d;
    }

    /** Minecraft's own rarity colours, so the call reads like the item's name would. */
    public static int colour(int tier) {
        return switch (tier) {
            case 3 -> 0xFFAA00;   // gold, legendary
            case 2 -> 0xAA00AA;   // dark purple, epic
            case 1 -> 0x5555FF;   // blue, rare
            default -> 0xAAAAAA;  // grey, common
        };
    }

    public static boolean passes(Drop d, String minTier) {
        return d.tier() >= TIERS.indexOf(minTier);
    }

    // ── the list ──────────────────────────────────────────────────────────────

    private static int tierOf(String item, int fallback) {
        if (rules == null) rules = load();
        String hay = item.toLowerCase(Locale.ROOT);
        for (Rule r : rules) if (hay.contains(r.needle())) return r.tier();
        return fallback;
    }

    /** Re-read the file. For after an edit, without a restart. */
    public static void reload() {
        rules = null;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("drops.txt");
    }

    private static List<Rule> load() {
        List<Rule> out = new ArrayList<>();
        try {
            Path f = file();
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                try (InputStream in = Drops.class.getResourceAsStream("/endsight-drops.txt")) {
                    if (in != null) Files.copy(in, f);
                }
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+", 2);
                if (parts.length < 2) continue;
                int tier = switch (parts[0].toLowerCase(Locale.ROOT)) {
                    case "legendary" -> 3;
                    case "epic" -> 2;
                    case "rare" -> 1;
                    case "common" -> 0;
                    default -> -1;
                };
                if (tier >= 0) out.add(new Rule(parts[1].trim().toLowerCase(Locale.ROOT), tier));
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not read drops.txt: " + e);
        }
        out.sort((x, y) -> y.needle().length() - x.needle().length());
        return out;
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private static int rank(String tier) {
        String t = tier.trim().toUpperCase(Locale.ROOT);
        if (t.contains("LEGENDARY") || t.contains("CRAZY") || t.contains("RNGESUS")) return 3;
        if (t.contains("EPIC")) return 2;
        if (t.contains("RARE")) return 1;
        if (t.contains("FLOOR") || t.contains("COMMON")) return 0;
        return 1;
    }

    /** White, grey and green sit below rare; blue is rare, purple epic, gold and beyond legendary. */
    private static int rankColour(String code) {
        if (code == null) return 1;
        return switch (code.charAt(1)) {
            case '9' -> 1;
            case '5' -> 2;
            case '6', 'd', 'b', 'c', '4' -> 3;
            default -> 0;
        };
    }
}
