package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's detailed loot roll, folded from eleven lines into two.
 *
 * "/debug detailed" prints the whole roll after a dragon - a banner, the dragon,
 * your rank and damage, the score, magic find, pet luck, the loot number, the armour
 * roll, the RNG meter, the result, a fragment count, and the bits - each on its own
 * line with a parenthetical explaining it, twelve lines that scroll everything else
 * off. The numbers are worth having; the prose around them is not, once you have
 * read it once. So the block is caught as it arrives, swallowed line by line, and
 * put back as two lines with only the numbers on them. The block has no closing
 * line - the first version waited for one and swallowed every roll whole - so it is
 * closed by the first line that is not part of it, or by a moment's silence. Any
 * line of the block that is not recognised is let through untouched, so a reworded
 * server still shows you everything it said.
 *
 * The same mode prints a drop table under every ordinary kill - "mob: NEST_ZEALOT
 * magic find: 332.53 (x3.33) pet luck: 62.7", the loot number with "(0-100, lower =
 * rarer)" after it, a line per drop with its chance and its slice of the roll, and
 * "result: nothing". Five lines a kill, the same five every kill. Hidden, behind a
 * toggle, because nobody reads a table they have memorised: the mob line, the table
 * and the empty result go, and the loot number is put back without its footnote so
 * the Loot Number Filter can judge it like any other. The rows the roll actually
 * landed in - marked ▶ - and a result that is something stay; those are the news.
 */
public final class LootRoll {

    private LootRoll() {
    }

    private static boolean enabled = true;
    private static boolean hideTables = true;

    private static final Pattern MOB = Pattern.compile("^mob:\\s+\\S+\\s+magic find:");
    private static final Pattern TABLE = Pattern.compile("^[\\d.]+%\\s*[\\[(][\\d.]+,\\s*[\\d.]+[\\])]");
    private static final Pattern NOTHING = Pattern.compile("^result:\\s*nothing");
    private static final Pattern FOOTNOTE = Pattern.compile("^loot number:\\s*([\\d.]+)\\s*\\(0-100");

    private static final Pattern DRAGON = Pattern.compile("^dragon:\\s*(.+?)\\s+rank:\\s*#?(\\d+)\\s+damage:\\s*([\\d,]+)");
    private static final Pattern SCORE = Pattern.compile("^drag score:\\s*([\\d,]+)");
    private static final Pattern MF = Pattern.compile("^magic find:\\s*([\\d.]+)");
    private static final Pattern PET = Pattern.compile("^pet luck:\\s*([\\d.]+)");
    private static final Pattern LOOT = Pattern.compile("^loot number:\\s*([\\d.]+)");
    private static final Pattern ARMOR = Pattern.compile("^armor roll:\\s*([\\d.]+)\\s*\\(needs\\s*[≤<=]*\\s*([\\d.]+)");
    private static final Pattern METER = Pattern.compile("^rng meter:\\s*(\\S+)(?:\\s*\\((x[\\d.]+)[^)]*\\))?");
    private static final Pattern RESULT = Pattern.compile("^result:\\s*(.+?)(?:\\s+frags:\\s*(\\d+))?\\s*(?:\\(.*)?$");
    private static final Pattern FROZEN = Pattern.compile("^frozen/crystal roll:\\s*([\\d.]+)");
    private static final Pattern BITS = Pattern.compile("^\\+([\\d,]+) Bits from the .*?\\(([\\d,]+) carrot bits left\\)");
    private static final String HEADER = "LOOT DEBUG";
    private static final String FOOTER = "Detailed loot debug disabled";
    private static final long BLOCK_MS = 4_000;

    private static long blockSince, lastLine;
    private static final Map<String, String> got = new LinkedHashMap<>();

    public static Module module() {
        return new Module("qol.lootroll", "Compact Loot Roll",
                "The detailed /debug roll after a dragon as two lines instead of twelve.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Hide drop tables",
                                "The mob line, drop chances, loot number ranges and empty results that detailed mode prints under every kill.",
                                () -> hideTables, v -> hideTables = v)));
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!enabled || overlay) return true;
            String line = Zealots.strip(message.getString()).trim();
            long now = System.currentTimeMillis();
            boolean inBlock = now - blockSince < BLOCK_MS;

            if (line.contains(HEADER)) {
                blockSince = now;
                lastLine = now;
                got.clear();
                return false;
            }
            if (inBlock) {
                // The rule of ═ above and below the banner, and the blank line the
                // server pads with - furniture, gone with the rest.
                if (line.isEmpty() || line.matches("^[═=\\-]+$")) {
                    lastLine = now;
                    return false;
                }
                if (line.startsWith(FOOTER)) {
                    emit();
                    return false;
                }
                if (read(line)) {
                    lastLine = now;
                    return false;
                }
                emit();                             // something else: the block is over,
            }                                       // and this line is judged like any other
            if (!hideTables) return true;
            if (MOB.matcher(line).find() || TABLE.matcher(line).find() || NOTHING.matcher(line).find()) return false;
            Matcher f = FOOTNOTE.matcher(line);
            if (f.find()) {
                // The number without its footnote. Sent again rather than edited - a
                // message cannot be changed on the way in - and the copy passes through
                // every filter the original would have, including the Loot Number Filter.
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§7loot number: §f" + f.group(1)));
                return false;
            }
            return true;
        });
        // A block that just stops - no footer, nothing said after it - is emitted after
        // a moment's silence rather than never.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (blockSince != 0 && !got.isEmpty() && System.currentTimeMillis() - lastLine > 700) emit();
        });
    }

    /** One line of the block into its field; false if it is not one we know. */
    private static boolean read(String line) {
        Matcher m;
        if ((m = DRAGON.matcher(line)).find()) {
            got.put("dragon", m.group(1));
            got.put("rank", m.group(2));
            got.put("damage", m.group(3));
        } else if ((m = SCORE.matcher(line)).find()) {
            got.put("score", m.group(1));
        } else if ((m = MF.matcher(line)).find()) {
            got.put("mf", m.group(1));
        } else if ((m = PET.matcher(line)).find()) {
            got.put("pet", m.group(1));
        } else if ((m = LOOT.matcher(line)).find()) {
            got.put("loot", m.group(1));
        } else if ((m = ARMOR.matcher(line)).find()) {
            got.put("armor", m.group(1));
            got.put("armorNeeds", m.group(2));
        } else if ((m = METER.matcher(line)).find()) {
            got.put("meter", m.group(1));
            if (m.group(2) != null) got.put("meterMult", m.group(2));
        } else if ((m = RESULT.matcher(line)).find()) {
            got.put("result", m.group(1).trim());
            if (m.group(2) != null) got.put("frags", m.group(2));
        } else if ((m = FROZEN.matcher(line)).find()) {
            got.put("frozen", m.group(1));
        } else if ((m = BITS.matcher(line)).find()) {
            got.put("bits", m.group(1));
            got.put("bitsLeft", m.group(2));
        } else {
            return false;
        }
        return true;
    }

    /** Two lines: who and how well, then the roll and what it gave. */
    private static void emit() {
        Minecraft mc = Minecraft.getInstance();
        blockSince = 0;
        if (mc.player == null || got.isEmpty()) return;

        // The dragon's name is the line above this one already, from the server, and
        // the RNG meter is a word nobody asked for - so: your place, your damage, the
        // score, MF and pet luck, then the roll and what it bought.
        StringBuilder a = new StringBuilder("§8▍ ");
        if (got.containsKey("rank")) a.append("§6#").append(got.get("rank")).append("  ");
        if (got.containsKey("damage")) a.append("§f").append(compact(got.get("damage"))).append("  ");
        if (got.containsKey("score")) a.append("§8score §f").append(got.get("score")).append("  ");
        if (got.containsKey("mf")) a.append("§8MF §f").append(whole(got.get("mf"))).append("  ");
        if (got.containsKey("pet")) a.append("§8PL §f").append(whole(got.get("pet")));

        StringBuilder b = new StringBuilder("§8▍ ");
        if (got.containsKey("loot")) {
            double v = num(got.get("loot"));
            String c = v < 1 || v > 99 ? "§d" : v < 5 || v > 95 ? "§e" : "§f";
            b.append("§8loot ").append(c).append(got.get("loot"));
        }
        if (got.containsKey("armor")) {
            double roll = num(got.get("armor")), need = num(got.getOrDefault("armorNeeds", "0"));
            b.append("  §8armor ").append(roll <= need ? "§a" : "§c").append(got.get("armor"))
                    .append("§8/").append(got.get("armorNeeds"));
        }
        if (got.containsKey("result")) {
            b.append("  §8→ §a").append(got.get("result"));
            if (got.containsKey("frags")) b.append(" §7×").append(got.get("frags"));
        }
        if (got.containsKey("bits")) b.append("  §b+").append(got.get("bits")).append(" bits");

        mc.player.sendSystemMessage(Component.literal(a.toString()));
        mc.player.sendSystemMessage(Component.literal(b.toString()));
        got.clear();
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String whole(String s) {
        return String.valueOf(Math.round(num(s)));
    }

    /** 57,861,010 → 57.9M, the way the server itself writes damage elsewhere. */
    private static String compact(String s) {
        double n = num(s);
        if (n >= 1e9) return trim(n / 1e9) + "B";
        if (n >= 1e6) return trim(n / 1e6) + "M";
        if (n >= 1e3) return trim(n / 1e3) + "k";
        return s;
    }

    private static String trim(double v) {
        String t = String.format("%.1f", v);
        return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
    }
}
