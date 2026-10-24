package com.endsight.storage;

import com.endsight.dragons.DragonTimer;
import com.endsight.zealots.Zealots;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Counts personal server results, never nearby chest entities or inventory transfers. */
public final class CacheLedger {
    private static final Pattern PLACED = Pattern.compile("^(?:☠\\s+)?You set down a Crypt Cache\\. It opens in \\d+ seconds?\\.$");
    private static final Pattern ROLLED = Pattern.compile("^(?:☠\\s+)?The Crypt Cache rolled like (.+?)!\\s+Loot number:\\s*[\\d.,]+$");
    private static final Pattern HEADER = Pattern.compile("^(?:☠\\s+)?Crypt Cache$");
    private static final Pattern PREFIX_AMOUNT = Pattern.compile("^([\\d,]+)[x×]\\s+(.+)$");
    private static final Pattern SUFFIX_AMOUNT = Pattern.compile("^(.+?)\\s+[x×]([\\d,]+)$");
    private static final Pattern COLOUR = Pattern.compile("§([0-9a-f])", Pattern.CASE_INSENSITIVE);
    private static final long SOURCE_MS = 10_000, LOOT_MS = 3_000;

    public static final class Counts {
        public long opened;
        public final Map<String,Integer> drops = new LinkedHashMap<>();
        public final Map<String,Integer> colours = new LinkedHashMap<>();

        public Counts copy() {
            Counts out = new Counts(); out.opened = opened;
            out.drops.putAll(drops); out.colours.putAll(colours); return out;
        }
    }

    public record Update(boolean changed, boolean cacheLine, boolean placed) {
        Update(boolean changed, boolean cacheLine) { this(changed, cacheLine, false); }
    }
    private Counts session = new Counts(), total = new Counts();
    private int headers;
    private long resultAt = -1, blockUntil = -1;
    private boolean block, started;
    private String rolledMob = "";
    private long revision;

    /** Each event is delivered once, through GAME or GAME_CANCELED, not both. */
    public Update message(String raw, long now) {
        boolean changed = false, claimed = false, placed = false;
        for (String part : raw.split("\\R", -1)) {
            Update result = line(part, now);
            changed |= result.changed(); claimed |= result.cacheLine(); placed |= result.placed();
        }
        return new Update(changed, claimed, placed);
    }

    private Update line(String raw, long now) {
        String plain = Zealots.strip(raw), line = plain.trim();
        if (DragonTimer.isPlayerChat(line)) return new Update(false, false);
        if (PLACED.matcher(line).matches()) { started = true; return new Update(false, true, true); }
        var rolled = ROLLED.matcher(line);
        if (rolled.matches()) {
            if (resultAt < 0 || now - resultAt > SOURCE_MS) headers = 0;
            headers++; resultAt = now; block = false; started = true;
            rolledMob = rolled.group(1);
            session.opened++; total.opened++;
            revision++;
            return new Update(true, true);
        }
        if (HEADER.matcher(line).matches()) {
            block = headers > 0 && resultAt >= 0 && now - resultAt <= SOURCE_MS;
            if (block) { headers--; blockUntil = now + LOOT_MS; }
            return new Update(false, block);
        }
        if (!block || now > blockUntil) { block = false; return new Update(false, false); }
        // Only the indented rows belonging to the private Cache loot block count.
        // A normal mob DROP! or a different source header ends the block immediately.
        if (!plain.startsWith("  ")) { block = false; return new Update(false, false); }
        if (line.isEmpty() || line.matches("(?i)(?:\\(?nothing\\)?[.!]?|no loot[.!]?)")) return new Update(false, true);
        int quantity = 1;
        String item = line;
        var prefix = PREFIX_AMOUNT.matcher(item);
        var suffix = SUFFIX_AMOUNT.matcher(item);
        try {
            if (prefix.matches()) { quantity = Integer.parseInt(prefix.group(1).replace(",", "")); item = prefix.group(2); }
            else if (suffix.matches()) { quantity = Integer.parseInt(suffix.group(2).replace(",", "")); item = suffix.group(1); }
        } catch (NumberFormatException ignored) { return new Update(false, true); }
        if (quantity <= 0 || item.isBlank()) return new Update(false, true);
        int colour = 0xFFFFFF;
        var codes = COLOUR.matcher(raw);
        while (codes.find()) colour = colour(codes.group(1).charAt(0));
        // Pet rarity is part of its identity; epic and legendary copies must not
        // collapse into one row merely because their species has the same name.
        if (item.matches("^\\[Lvl \\d+\\].*") && !item.matches(".*\\((?:Common|Uncommon|Rare|Epic|Legendary|Mythic)\\)$")) {
            String rarity = rarity(colour);
            if (!rarity.isEmpty()) item += " (" + rarity + ")";
        }
        for (Counts counts : new Counts[]{session, total}) {
            counts.drops.merge(item, quantity, CacheLedger::add);
            counts.colours.put(item, colour);
        }
        revision++;
        return new Update(true, true);
    }

    private static int add(int a, int b) { return (int)Math.min(Integer.MAX_VALUE, (long)a + b); }
    private static int colour(char code) {
        String codes = "0123456789abcdef";
        int[] values = {0x000000,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,
                0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};
        return values[codes.indexOf(Character.toLowerCase(code))];
    }
    private static String rarity(int colour) {
        return switch (colour) {
            case 0xFFFFFF -> "Common"; case 0x55FF55 -> "Uncommon"; case 0x5555FF -> "Rare";
            case 0xAA00AA -> "Epic"; case 0xFFAA00 -> "Legendary"; case 0xFF55FF -> "Mythic"; default -> "";
        };
    }

    public Counts counts(boolean allTime) { return (allTime ? total : session).copy(); }
    public void restore(Counts counts) { total = counts.copy(); revision++; }
    public void resetSession() { session = new Counts(); revision++; }
    public void resetSource() { headers = 0; resultAt = blockUntil = -1; block = started = false; }
    public boolean started() { return started; }
    public String rolledMob() { return rolledMob; }
    public long revision() { return revision; }
    public long opened(boolean allTime) { return (allTime ? total : session).opened; }
}
