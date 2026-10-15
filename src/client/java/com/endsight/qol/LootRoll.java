package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's /debug detailed roll, one line a kill.
 *
 * Detailed mode prints a block under every kill: a "LOOT DEBUG" banner, the mob with
 * your magic find and pet luck, the loot number, a row per rare drop with its chance
 * and its slice of the roll, the result, and a "rare roll" line for each separate
 * chance the mob carries (a Special Zealot, a Goop). Eight to ten lines a kill, the
 * same lines every kill, and a few kills a second at the nest. The block is caught as
 * it arrives and put back as one line - the mob, the roll, what it gave, the rare
 * rolls - with the exact adjusted chances and ranges in its hover and details panel.
 * The base comparison is omitted from those views. A dragon's block folds to two lines.
 *
 * Older blocks have no closing line, and they are not contiguous: in the log a "RARE DROP!"
 * for the Summoning Eye lands between a kill's result and its rare roll. So other
 * lines are let through as they come, the block stays open around them, and it closes
 * on the next banner or a few ticks of quiet - every line of one arrives in the same
 * server tick. The first version closed on the first line it did not know and spilled
 * the rest raw; the old dragon fold did the same on "armor roll: skipped".
 *
 * The Loot Number Filter judges the block here as well, whole: a kill shows only if
 * it dropped something, hit a rare roll, or either roll was a close call by the
 * filter's own rule. Before, the filter could only hide the one loot number line and
 * left the table, the result and the rare roll under it - which is why it seemed not
 * to work on detailed at all. With this module off and the filter on, a kill that
 * passes is put back exactly as the server sent it.
 */
public final class LootRoll {

    private LootRoll() {
    }

    private static boolean enabled = true;

    private static final String HEADER = "LOOT DEBUG";
    private static final String FOOTER = "Detailed loot debug disabled";
    private static final Pattern BANNER = Pattern.compile("^[═=\\-▬─━\\s]*LOOT DEBUG[═=\\-▬─━\\s]*$");
    private static final Pattern END = Pattern.compile("^[═=\\-▬─━\\s]*(?:END LOOT DEBUG|LOOT DEBUG END)[═=\\-▬─━\\s]*$");

    // "mob: ?" means the server did not identify the source. This also occurs on
    // Voidgloom boss loot, so the line's context must supply a useful label.
    private static final Pattern MOB = Pattern.compile("^mob:\\s+(\\S+)\\s+magic find:\\s*([\\d.]+)(?:.*?pet luck:\\s*([\\d.]+))?");
    // October's server format includes base and effective chances before the slice.
    // Keep the slice captures unchanged: close-call colour uses the adjusted bounds.
    private static final Pattern ROW = Pattern.compile("^(▶\\s*)?(?:base\\s+[\\d.]+%\\s*→\\s*effective\\s+)?[\\d.]+%\\s*[\\[(]([\\d.]+),\\s*([\\d.]+)[\\])]\\s*(.+)$");
    private static final Pattern RARE = Pattern.compile("^(▶\\s*)?rare roll:?\\s+(.+?)\\s*\\(1 in ([\\d,]+),\\s*([\\d.]+)%\\)\\s*rolled\\s*([\\d.]+)\\s*(\\w+)");
    // A streak's extra drop also has the server's short, hit-only annotation. It
    // carries no rolled value and must never become the primary table result.
    private static final Pattern BONUS_RARE = Pattern.compile("^(?:▶\\s*)?rare roll:?\\s+(bonus\\s+.+?)\\s*\\(1 in ([\\d,]+)\\)\\s*$");
    private static final Pattern BONUS_RESULT = Pattern.compile("^bonus(?:\\s*:\\s*|\\s+)(.+)$", Pattern.CASE_INSENSITIVE);
    // Both. Only a bare number: "loot number: 7 → Golden Eye" is the drop trackers'
    // line and is never taken.
    private static final Pattern LOOT = Pattern.compile("^loot number:\\s*([\\d.]+)\\s*(?:\\((?:0-100[^)]*|the rare table, lower\\s*=\\s*rarer)\\))?\\s*$");
    private static final Pattern RESULT = Pattern.compile("^result:\\s*(.+?)(?:\\s+frags:\\s*(\\d+))?\\s*(?:\\(.*)?$");
    // A dragon.
    private static final Pattern DRAGON = Pattern.compile("^dragon:\\s*(.+?)\\s+rank:\\s*#?(\\d+)\\s+damage:\\s*([\\d,]+)");
    private static final Pattern SCORE = Pattern.compile("^drag score:\\s*([\\d,]+)");
    private static final Pattern MF = Pattern.compile("^magic find:\\s*([\\d.]+)(?:.*?pet luck:\\s*([\\d.]+))?");
    private static final Pattern PET = Pattern.compile("^pet luck:\\s*([\\d.]+)");
    private static final Pattern ARMOR = Pattern.compile("^armor roll:\\s*(?:([\\d.]+)\\s*\\(needs\\s*([≤<=]*)\\s*([\\d.]+)(?:\\s+and\\s*[≤<=]+\\s*([\\d.]+))?|skipped)");
    private static final Pattern METER = Pattern.compile("^rng meter:\\s*(\\S+)");
    private static final Pattern FROZEN = Pattern.compile("^frozen/crystal roll:\\s*([\\d.]+)");
    private static final Pattern BITS = Pattern.compile("^\\+([\\d,]+) Bits from the .*?\\(([\\d,]+) carrot bits left\\)");

    /** Every line of a block comes in one server tick; this much quiet means it is over. */
    private static final long QUIET_MS = 250;

    private static Block open;
    private static long lastLine;
    /** Our own lines go back through the chat events; this lets them past. */
    private static boolean sending;

    public static Module module() {
        return new Module("qol.lootroll", "Compact Loot Roll",
                "Your /debug detailed rolls, one line a kill.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of());
    }

    /** True while a block is being read or put back, so the Loot Number Filter leaves its lines to this. */
    static boolean busy() {
        return open != null || sending;
    }

    public static void init() {
        LootRollDetails.init();
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || sending || !(enabled || LootFilter.on())) return true;
            String line = Zealots.strip(message.getString()).trim();
            long now = System.currentTimeMillis();

            if (debugHeader(line)) {
                flush();
                open = new Block();
                open.raw.add(message);
                lastLine = now;
                return false;
            }
            if (line.startsWith(FOOTER)) {
                flush();
                return true;
            }
            if (debugEnd(line)) {
                boolean hadBlock = open != null;
                if (hadBlock) open.raw.add(message);
                flush();
                return !hadBlock;
            }
            if (open != null && open.startsNext(line)) flush();
            if (open == null) {
                // A kill printed without its banner still gets folded; anything else
                // outside a block is not ours.
                if (!rollAnchor(line)) return true;
                open = new Block();
            }
            open.observe(line);
            // The rules either side of the banner and the blank line the server pads with.
            if (line.isEmpty() || line.matches("^[═=\\-▬]+$") || open.read(line)) {
                open.raw.add(message);
                lastLine = now;
                return false;
            }
            return true;                        // not ours: through, and the block stays open
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (open != null && System.currentTimeMillis() - lastLine > QUIET_MS) flush();
        });
    }

    static boolean debugHeader(String line) {
        return BANNER.matcher(line).matches();
    }

    static boolean debugEnd(String line) {
        return END.matcher(line).matches();
    }

    static boolean rollAnchor(String line) {
        return MOB.matcher(line).find() || DRAGON.matcher(line).find();
    }

    private static void flush() {
        Block b = open;
        open = null;
        if (b == null || !b.read || !b.worthShowing()) return;
        if (!enabled) {
            for (Component c : b.raw) send(c);  // only the filter is on: the server's own lines
        } else if (b.mob == null && !b.dragon.isEmpty()) {
            send(hover(dragonTop(b), b));
            send(hover(dragonRoll(b), b));
        } else {
            send(hover(kill(b), b));
        }
    }

    // ---------------------------------------------------------------- the lines

    /**
     * "▍ Zealot 0.412 · Special Zealot 27.86": the mob, the loot number, what it gave,
     * then each rare roll. A number goes yellow when it came within double of
     * something; a hit is a green tick and a drop is green.
     */
    static String kill(Block b) {
        List<String> parts = new ArrayList<>();
        if (b.mob != null) parts.add("§f" + (b.mob.equals("?")
                ? b.slayerLoot ? "Slayer loot" : "Loot roll"
                : name(b.mob)));
        if (b.loot >= 0) parts.add(lootColour(b) + fmt(b.loot));
        if (b.dropped()) parts.add("§8→ §a" + b.result);
        for (Rare r : b.rares) {
            parts.add(r.hit ? "§8· §a✔ " + r.name
                    : "§8· §7" + r.name + (r.rolled < r.needs * 2 ? " §e" : " §8") + fmt(r.rolled));
        }
        return "§8▍ " + String.join(" ", parts);
    }

    private static String lootColour(Block b) {
        if (b.dropped()) return "§a";
        double v = b.loot;
        if (v >= 99) return "§d";
        double top = 0;
        for (double[] row : b.rows) top = Math.max(top, row[1]);
        // With a table there is something to have nearly hit; without one, only rarity.
        if (top > 0) return v < top * 2 ? "§e" : "§f";
        return v < 1 ? "§d" : v < 5 ? "§e" : "§f";
    }

    /** Only the loot-relevant stats; placement and damage already appear in the server's recap. */
    static String dragonTop(Block b) {
        Map<String, String> d = b.dragon;
        List<String> parts = new ArrayList<>();
        if (d.containsKey("score")) parts.add("§7Score §f" + d.get("score"));
        if (d.containsKey("mf")) parts.add("§7MF §b" + Math.round(num(d.get("mf"))));
        if (d.containsKey("pet")) parts.add("§7PL §d" + Math.round(num(d.get("pet"))));
        return "§8▍ " + String.join(" §8· ", parts);
    }

    /** The roll and what it bought. */
    static String dragonRoll(Block b) {
        Map<String, String> d = b.dragon;
        List<String> parts = new ArrayList<>();
        if (b.loot >= 0) parts.add("§7Loot " + lootColour(b) + fmt(b.loot));
        String line = "§8▍ " + String.join(" §8· ", parts);
        // The old ×N suffix was the fragment share, making one pair of boots read as six.
        if (b.dropped()) line += (parts.isEmpty() ? "" : " ") + "§8→ §a" + b.result;
        if (d.containsKey("bits")) line += " §8· §b+" + d.get("bits") + " bits";
        return line;
    }

    /** Clicking pins the normal hover so its individual lines can be selected. */
    static Component hover(String text, Block b) {
        MutableComponent all = Component.empty();
        boolean first = true;
        for (Component c : b.raw) {
            String plain = Zealots.strip(c.getString()).trim();
            if (plain.isEmpty() || plain.contains(HEADER) || plain.matches("^[═=\\-▬]+$")) continue;
            if (!first) all.append("\n");
            all.append(LootRollDetails.displayLine(c));
            first = false;
        }
        if (!first) all.append("\n\n");
        all.append(Component.literal("§eClick to pin this hover"));
        return Component.literal(text).withStyle(s -> s
                .withHoverEvent(new HoverEvent.ShowText(all))
                .withClickEvent(LootRollDetails.action(b.raw)));
    }

    private static void send(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        sending = true;
        try {
            mc.player.sendSystemMessage(c);
        } finally {
            sending = false;
        }
    }

    // ---------------------------------------------------------------- one block

    record Rare(String name, double needs, double rolled, boolean hit) {
    }

    static final class Block {
        final List<Component> raw = new ArrayList<>();
        final List<double[]> rows = new ArrayList<>();
        final List<Rare> rares = new ArrayList<>();
        final Map<String, String> dragon = new LinkedHashMap<>();
        String mob, result;
        boolean slayerLoot;
        double loot = -1;
        /** Whether any line of the roll itself was read - a banner alone is not a kill. */
        boolean read;

        boolean startsNext(String line) {
            if (MOB.matcher(line).find()) return mob != null || !dragon.isEmpty();
            return DRAGON.matcher(line).find() && (mob != null || dragon.containsKey("rank"));
        }

        /** One line into its field; false if it is not one of ours. */
        boolean read(String line) {
            Matcher m;
            if ((m = MOB.matcher(line)).find()) {
                mob = m.group(1);
            } else if ((m = ROW.matcher(line)).find()) {
                rows.add(new double[]{num(m.group(2)), num(m.group(3))});
            } else if ((m = RARE.matcher(line)).find()) {
                addRare(new Rare(m.group(2), num(m.group(4)), num(m.group(5)),
                        m.group(6).equalsIgnoreCase("hit")));
            } else if ((m = BONUS_RARE.matcher(line)).matches()) {
                double odds = num(m.group(2));
                if (odds <= 0) return false;
                addBonus(m.group(1), 100.0 / odds);
            } else if ((m = LOOT.matcher(line)).find()) {
                // Dragon rolls now carry the rare-table annotation too. Letting it
                // through raw also left the folded summary without its loot number.
                loot = num(m.group(1));
            } else if ((m = RESULT.matcher(line)).find()) {
                String found = m.group(1).trim();
                Matcher bonus = BONUS_RESULT.matcher(found);
                if (bonus.matches()) {
                    addBonus("bonus " + bonus.group(1), Double.NaN);
                } else if (result == null) {
                    result = found;
                    if (m.group(2) != null) dragon.put("frags", m.group(2));
                } else if (!result.equalsIgnoreCase(found) && !isBonusResult(found)) {
                    // A second unexplained result is not permission to replace the
                    // real drop (or to label another kill's result as a streak bonus).
                    // Leave it visible as received until its own anchor arrives.
                    return false;
                }
            } else if ((m = DRAGON.matcher(line)).find()) {
                dragon.put("rank", m.group(2));
                dragon.put("damage", m.group(3));
            } else if ((m = SCORE.matcher(line)).find()) {
                dragon.put("score", m.group(1));
            } else if ((m = MF.matcher(line)).find()) {
                dragon.put("mf", m.group(1));
                if (m.group(2) != null) dragon.put("pet", m.group(2));
            } else if ((m = PET.matcher(line)).find()) {
                dragon.put("pet", m.group(1));
            } else if ((m = ARMOR.matcher(line)).find()) {
                // "skipped (table hit or score < 350 with no eyes)" has no numbers to show.
                if (m.group(1) != null) {
                    dragon.put("armor", m.group(1));
                    dragon.put("armorOp", m.group(2));
                    dragon.put("armorNeeds", m.group(3));
                    if (m.group(4) != null) dragon.put("armorCap", m.group(4));
                }
            } else if (METER.matcher(line).find() || FROZEN.matcher(line).find()) {
                // Kept in the hover only: the meter is a word nobody asked for, and the
                // frozen roll is announced on its own when it lands.
            } else if (!dragon.isEmpty() && (m = BITS.matcher(line)).find()) {
                dragon.put("bits", m.group(1));
            } else {
                return false;
            }
            read = true;
            return true;
        }

        private boolean isBonusResult(String item) {
            for (Rare rare : rares) {
                Matcher bonus = BONUS_RESULT.matcher(rare.name());
                if (rare.hit() && bonus.matches() && bonus.group(1).equalsIgnoreCase(item)) return true;
            }
            return false;
        }

        private void addBonus(String name, double chance) {
            // NaN is deliberate: a short hit annotation never reported a rolled value.
            // Hit rendering and filter decisions use the confirmed outcome directly.
            addRare(new Rare(name, chance, Double.NaN, true));
        }

        private void addRare(Rare rare) {
            if (rare.hit() && BONUS_RESULT.matcher(rare.name()).matches()) {
                for (int i = 0; i < rares.size(); i++) {
                    Rare old = rares.get(i);
                    if (!old.hit() || !old.name().equalsIgnoreCase(rare.name())) continue;
                    if (Double.isNaN(old.rolled()) && !Double.isNaN(rare.rolled())) rares.set(i, rare);
                    return;
                }
            }
            rares.add(rare);
        }

        void observe(String line) {
            if (line.startsWith("NICE! SLAYER BOSS SLAIN!") || line.startsWith("SLAYER BOSS SLAIN!")) slayerLoot = true;
        }

        boolean dropped() {
            return result != null && !result.equalsIgnoreCase("nothing");
        }

        /** The Loot Number Filter's call on the whole kill. */
        boolean worthShowing() {
            if (!LootFilter.on() || dropped()) return true;
            if (loot >= 0 && LootFilter.shows(loot)) return true;
            for (Rare r : rares) if (r.hit || LootFilter.shows(r.rolled)) return true;
            return false;
        }
    }

    // ---------------------------------------------------------------- numbers and names

    /** NEST_ZEALOT -> Zealot. An unknown source is labeled by its block context. */
    private static String name(String mob) {
        String s = mob.startsWith("NEST_") ? mob.substring(5) : mob;
        StringBuilder out = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * Enough decimals to see how close a roll came: three significant figures of its
     * distance from the nearer end. A 0.15001 against a 0.15290 chance needs the third
     * place; a 54.16 does not need a fifth.
     */
    static String fmt(double v) {
        double near = Math.max(1e-5, Math.min(v, 100 - v));
        int places = Math.max(2, Math.min(5, 2 - (int) Math.floor(Math.log10(near))));
        String s = String.format(Locale.ROOT, "%." + places + "f", v);
        while (s.endsWith("0") && s.indexOf('.') < s.length() - 3) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (RuntimeException e) {
            return 0;
        }
    }

}
