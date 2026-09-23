package com.endsight.storage;

import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import com.endsight.ui.Draw;
import com.endsight.zealots.Zealots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How long until the next Void Fragment, from everything the server lets us see.
 *
 * A fragment is a 1-in-100,000 roll on every amethyst block. What moves that number:
 * Magic Find (the server prints the factor it uses in its loot debug - 170.5 MF was
 * x1.71, so it is 1 + MF/240, not the 1 + MF/100 of other games), the RNG meter when
 * the fragment is the selected drop (+10% at empty, +25% just before it fills - read
 * off two of its own tooltips - and a guaranteed drop at 100,000), Void Seeker (+2% a
 * level), Gifts from the Void (+0.2% a level chance of a second one), and a Scatha pet
 * (Burrowing: a Legendary one is +30%; the other rarities are learned off the first
 * Scatha tooltip of that rarity that goes past, since nobody has written them down).
 *
 * Nothing here is typed in. Magic Find comes off the action bar every second, the meter
 * off its menu whenever it is opened, the perks off the Heart of the Dragon when that
 * is, and the block pace off the powder tracker's own count. What has not been seen
 * yet is taken at its lowest: no meter until one is read or switched on, no perks
 * until the tree is opened. The number can only get better as menus are opened.
 */
public final class VoidFragments {

    private VoidFragments() {
    }

    static final String ID = "mining.fragments";
    private static final String METER = "Amethyst Block RNG Meter";
    private static final String FRAGMENT = "Void Fragment";
    private static final Pattern MAGIC_FIND = Pattern.compile("([\\d,]+)\\s*✯\\s*Magic Find");
    private static final Pattern ODDS = Pattern.compile("Odds:\\s*1/([\\d,]+)");
    private static final Pattern KILLS = Pattern.compile("Kills:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");
    private static final Pattern PET = Pattern.compile("Pet:\\s*(?:§.)*\\[Lvl \\d+\\]\\s*(§.)?(.+)");
    private static final Pattern BURROWING = Pattern.compile("(\\d+)% higher chance to find a Void Fragment");
    private static final Pattern PET_RARITY = Pattern.compile("^(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC) PET");

    public static boolean show = true;
    /** The fragment is the meter's selected drop. Off until a meter says so: the lowest buff. */
    public static boolean meterOn = false;

    private static int magicFind;
    private static int seeker, gifts, core;
    private static long baseOdds = 100_000;
    /** The meter as last read, and the tracker's raw count at that moment, so it can be walked forward. */
    private static long meterBlocks = -1, meterNeed = 100_000, rawAtRead;
    private static long lastSave;
    private static double lastPace;
    /** The pet on the sidebar, and Burrowing by rarity as seen on Scatha tooltips. */
    private static String pet = "", petRarity = "";
    /**
     * Burrowing by rarity, logged from real Scatha tooltips: Rare 30% (its one perk) and
     * Legendary 50% (the first of three). Epic is estimated at 40%, the midpoint - a
     * tier up has so far been a flat step on both pets seen, and an Epic keeps
     * Burrowing and Drill Infusion but loses the third perk. A real Epic tooltip, if
     * one ever goes past, replaces the estimate and is saved.
     */
    private static final java.util.Map<String, Integer> burrowing =
            new java.util.HashMap<>(java.util.Map.of("RARE", 30, "EPIC", 40, "LEGENDARY", 50));
    private static int tickN;
    /**
     * The rarities whose Burrowing came off a real tooltip. Only these are saved: the
     * file used to hold the defaults too, so the first build's wrong Legendary 30% sat
     * in the file and beat the corrected 50% in the code on every launch after.
     */
    private static final java.util.Set<String> seenBurrowing = new java.util.HashSet<>();

    public static void init() {
        load();
        // Any Scatha tooltip that goes past - AH, pet menu, someone's trade - teaches
        // its rarity's Burrowing. Lore is the same for everyone, so once is enough.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!Zealots.strip(stack.getHoverName().getString()).contains("Scatha")) return;
            int pct = -1;
            String rarity = null;
            // Read as one sentence: the game wraps lore, and "Grants a 50% higher chance
            // to" / "find a Void Fragment while mining." are two lines. Matched a line at
            // a time, the pattern never saw the whole phrase and this never learned a thing.
            StringBuilder all = new StringBuilder();
            for (Component c : lines) {
                String line = Zealots.strip(c.getString()).trim();
                all.append(line).append(' ');
                Matcher r = PET_RARITY.matcher(line);
                if (r.find()) rarity = r.group(1);
            }
            Matcher b = BURROWING.matcher(all.toString().replaceAll("\s+", " "));
            if (b.find()) pct = (int) num(b.group(1));
            if (pct >= 0 && rarity != null && !Integer.valueOf(pct).equals(burrowing.get(rarity))) {
                // What the game says beats what we assumed, either way: the Epic guess
                // has to be correctable down as well as up.
                burrowing.put(rarity, pct);
                seenBurrowing.add(rarity);
                save();
            }
        });
    }

    /** Once a second: which pet is out, by the sidebar line and the colour of its name. */
    static void tick(Minecraft mc) {
        if (++tickN % 20 != 0) return;
        String raw = Area.sidebarLine(mc, "Pet:");
        if (raw == null) return;
        Matcher m = PET.matcher(raw);
        if (!m.find()) return;
        pet = Zealots.strip(m.group(2)).trim();
        String code = m.group(1) == null ? "" : m.group(1).substring(1).toLowerCase(Locale.ROOT);
        petRarity = switch (code) {
            case "6" -> "LEGENDARY";
            case "5" -> "EPIC";
            case "9" -> "RARE";
            case "a" -> "UNCOMMON";
            case "f" -> "COMMON";
            case "d" -> "MYTHIC";
            default -> "";
        };
        PowderTracker.petChanged((petRarity + " " + pet).trim());
    }

    /** The Scatha's Burrowing, if a Scatha is out and its rarity's number is known. */
    private static double petBonus() {
        if (!pet.contains("Scatha")) return 0;
        return burrowing.getOrDefault(petRarity, 0) / 100.0;
    }

    // ---- what the server shows us ----

    /** The action bar carries "168✯ Magic Find" with every health tick. */
    public static void onActionBar(String text) {
        Matcher m = MAGIC_FIND.matcher(Zealots.strip(text));
        if (m.find()) magicFind = (int) num(m.group(1));
    }

    /** A Heart of the Dragon perk with its level, as the tracker reads the tree. */
    public static void perk(String name, int level) {
        if (level < 0) return;
        if (name.equalsIgnoreCase("Void Seeker")) seeker = level;
        else if (name.equalsIgnoreCase("Gifts from the Void")) gifts = level;
        else if (name.equalsIgnoreCase("Core of the Dragon")) {
            // Its ninth level is "+100% Void Fragment chance" - a doubling, and the
            // biggest single thing on the whole estimate. The levels are permanent and
            // a reset does not touch them, so once seen it stays true.
            core = level;
            save();
        }
    }

    public static boolean isMeter(String title) {
        return METER.equalsIgnoreCase(title);
    }

    /** The meter menu: the fragment's own tooltip says its odds, its count and whether it is selected. */
    public static void scanMeter(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Slot slot : screen.getMenu().slots) {
            ItemStack s = slot.getItem();
            if (s.isEmpty() || slot.container == mc.player.getInventory()) continue;
            if (!Zealots.strip(s.getHoverName().getString()).trim().equalsIgnoreCase(FRAGMENT)) continue;
            boolean selected = false;
            for (Component c : s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
                String line = Zealots.strip(c.getString()).trim();
                Matcher o = ODDS.matcher(line);
                if (o.find()) baseOdds = (long) num(o.group(1));
                Matcher k = KILLS.matcher(line);
                if (k.find()) {
                    meterBlocks = (long) num(k.group(1));
                    meterNeed = (long) num(k.group(2));
                    rawAtRead = PowderTracker.rawBlocks(PowderTracker.AMETHYST);
                }
                if (line.equalsIgnoreCase("SELECTED")) selected = true;
            }
            meterOn = selected;
            save();
            return;
        }
    }

    // ---- the maths ----

    private static long progressBlocks() {
        if (meterBlocks < 0) return 0;
        return meterBlocks + Math.max(0, PowderTracker.rawBlocks(PowderTracker.AMETHYST) - rawAtRead);
    }

    /** The meter's own bonus: +10% empty, +25% full, as its tooltips read at two points. */
    private static double meterBonus() {
        if (!meterOn) return 0;
        double progress = meterNeed <= 0 ? 0 : Math.min(1.0, progressBlocks() / (double) meterNeed);
        return 0.10 + 0.15 * progress;
    }

    private static double perBlock() {
        return (1.0 / baseOdds) * (1 + meterBonus()) * (1 + magicFind / 240.0) * (1 + 0.02 * seeker)
                * (1 + petBonus()) * (core >= 9 ? 2 : 1);
    }

    /**
     * Expected blocks to the next one. With the meter on, the drop is forced when the
     * count runs out, so it is the mean of a roll capped at the blocks left; without,
     * it is just the odds.
     */
    private static double blocksToNext() {
        double p = perBlock();
        if (!meterOn) return 1 / p;
        double left = Math.max(1, meterNeed - progressBlocks());
        return (1 - Math.exp(-p * left)) / p;
    }

    // ---- the line ----

    /**
     * The fragment line, as a value for the MINING readout: "1 per 2h 33m". It lives
     * inside that readout now rather than as a line of its own - it only means
     * anything while you mine amethyst, which is exactly when that readout is up.
     * Empty when there is no pace yet, so the row is left out rather than guessing.
     */
    public static String row(boolean sample) {
        if (sample) return "1 per 2h 33m";
        // Not off the first seconds. Thirty-seven seconds in, six blocks made "1 per
        // 31h 48m" - true to the six blocks and useless. Two minutes of mining first.
        if (PowderTracker.activeMs() < 120_000) return "measuring";
        double pace = PowderTracker.blocksPerHour(PowderTracker.AMETHYST);
        if (pace > 0) lastPace = pace;
        if (lastPace <= 0) return "";
        double hours = blocksToNext() / lastPace / (1 + 0.002 * gifts);
        return "1 per " + hours(hours);
    }

    private static String hours(double h) {
        if (h >= 48) return String.format(Locale.ROOT, "%.1fd", h / 24);
        int hh = (int) h, mm = (int) Math.round((h - hh) * 60);
        if (mm == 60) { hh++; mm = 0; }
        return hh > 0 ? hh + "h " + mm + "m" : mm + "m";
    }

    private static String compact(long n) {
        return n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k") : String.valueOf(n);
    }

    // ---- kept between sessions ----

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("endsight").resolve("rng-meter.txt");
    }

    /** Written when the meter is read and every few minutes after, walked forward by the blocks since. */
    static void maybeSave() {
        long now = System.currentTimeMillis();
        if (meterBlocks >= 0 && now - lastSave > 300_000) save();
    }

    private static void save() {
        lastSave = System.currentTimeMillis();
        Properties p = new Properties();
        p.setProperty("meterOn", String.valueOf(meterOn));
        p.setProperty("meterBlocks", String.valueOf(progressBlocks()));
        p.setProperty("meterNeed", String.valueOf(meterNeed));
        p.setProperty("baseOdds", String.valueOf(baseOdds));
        p.setProperty("seeker", String.valueOf(seeker));
        p.setProperty("gifts", String.valueOf(gifts));
        p.setProperty("core", String.valueOf(core));
        for (String r : seenBurrowing) p.setProperty("seen." + r, String.valueOf(burrowing.get(r)));
        try {
            Files.createDirectories(file().getParent());
            try (var out = Files.newBufferedWriter(file())) {
                p.store(out, "Endsight RNG meter, as last read");
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
        meterOn = Boolean.parseBoolean(p.getProperty("meterOn", "false"));
        meterBlocks = (long) num(p.getProperty("meterBlocks", "-1"));
        meterNeed = (long) num(p.getProperty("meterNeed", "100000"));
        baseOdds = (long) num(p.getProperty("baseOdds", "100000"));
        seeker = (int) num(p.getProperty("seeker", "0"));
        gifts = (int) num(p.getProperty("gifts", "0"));
        core = (int) num(p.getProperty("core", "0"));
        // "burrowing.*" in older files were the defaults as much as anything seen; they
        // are ignored. "seen.*" is only ever a real tooltip.
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("seen.")) continue;
            burrowing.put(k.substring(5), (int) num(p.getProperty(k)));
            seenBurrowing.add(k.substring(5));
        }
        rawAtRead = 0;   // a new session's count starts at zero; the saved figure already holds the old blocks
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
