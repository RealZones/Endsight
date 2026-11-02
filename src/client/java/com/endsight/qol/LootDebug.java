package com.endsight.qol;

import java.util.regex.Pattern;

/** Personal server debug headers, shared by chat folding and kill attribution. */
public final class LootDebug {
    private LootDebug() { }

    private static final Pattern OLD_MOB = Pattern.compile("^mob:\\s+(\\S+)\\s+magic find:\\s*([\\d.]+)(?:.*?pet luck:\\s*([\\d.]+))?");
    private static final Pattern MOB = Pattern.compile("^\\[Debug\\]\\s+([A-Z][A-Z0-9_]*|\\?)\\s+MF\\s+([\\d.]+)\\s+PL\\s+([\\d.]+)\\s+Luck\\s+[\\d.]+x$");
    private static final Pattern OLD_DRAGON = Pattern.compile("^dragon:\\s*(.+?)\\s+rank:\\s*#?(\\d+)\\s+damage:\\s*([\\d,]+)");
    private static final Pattern DRAGON = Pattern.compile("^\\[Debug\\]\\s+(.+? Dragon)\\s+#(\\d+)\\s+Score\\s+([\\d,]+)\\s+\\(([\\d,]+) eyes?\\)\\s+Damage\\s+([\\d,]+)$");
    private static final Pattern RARE = Pattern.compile("^(?:▶\\s*)?\\[Roll\\]\\s+(.+?)\\s+([\\d.]+)\\s+<\\s+([\\d.]+)%\\s+\\(1 in ([\\d,]+)\\)\\s+(HIT|miss)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NORMAL = Pattern.compile("^LootNum:\\s*([\\d.]+)\\s+Magic Find Multiplier:\\s*[\\d.]+x$");
    // /debug lets players choose which fields appear. The roll is structural; MF, PL
    // and Luck are optional, and their exact order can vary. The source is a field like
    // the rest: with it off the line opens on the roll, "[Debug] Roll 25.50295/100 · MF ...",
    // and requiring it left every default line in Fear's November 1 log unread.
    private static final Pattern MODERN = Pattern.compile("^\\[Debug\\]\\s+(?:(.+?)\\s+·\\s+)?(Roll|Table)\\s+([\\d.]+)/100(?:\\s+·\\s+(.+))?$");
    // Figures about a roll rather than what it gave. Kept in the result, the default's
    // closing "Odds 1/6" made every seasonal roll read as the table's own, since a rare
    // roll is told apart by its result ending in hit or miss.
    private static final Pattern FIGURE = Pattern.compile(
            "^(?:Odds\\s+.+|[\\d,]+\\s+items?\\s+odds\\b.*|Base\\s+[\\d.]+%|(?:Armor\\s+)?[Rr]ange\\s+\\[.*"
                    + "|Hit\\s+[\\d.]+%|Meter\\s+.+|Score\\s+[\\d,]+)$");
    private static final Pattern STAT = Pattern.compile("^(?:(?:Miss\\s+)?Chance\\s+[\\d.]+%|(?:MF|PL)\\s+[\\d.]+|Luck\\s+[\\d.]+x)$");

    public record Mob(String id, String magicFind, String petLuck) { }
    public record Dragon(String name, String rank, String damage, String score, String eyes) { }
    public record Rare(String name, double chance, double rolled, boolean hit) { }
    public record Modern(String mob, double roll, String magicFind, String petLuck, String result, boolean main, boolean hit) {
        /** What the roll gave, without the Luck before it or the chance after: "miss", "Special Zealot HIT", "Golden Eye". */
        public String outcome() {
            return result.replaceFirst("^Luck\\s+[\\d.]+x(?:\\s*·\\s*|$)", "")
                    .replaceFirst("(?i)(?:^|\\s*·\\s*)(?:Miss )?Chance\\s+[\\d.]+%$", "").strip();
        }
    }

    public static Modern modern(String line) {
        if (line == null) return null;
        var m = MODERN.matcher(line);
        if (!m.matches()) return null;
        try {
            String mf = null, pl = null;
            StringBuilder result = new StringBuilder();
            if (m.group(4) != null) for (String part : m.group(4).split("\\s+·\\s+")) {
                if (part.matches("MF\\s+[\\d.]+")) mf = part.substring(3);
                else if (part.matches("PL\\s+[\\d.]+")) pl = part.substring(3);
                else if (!FIGURE.matcher(part).matches()) {
                    if (!result.isEmpty()) result.append(" · ");
                    result.append(part);
                }
            }
            String value = result.toString();
            boolean main = m.group(2).equals("Table") || value.startsWith("Luck ")
                    || value.matches("(?i).*\\bMiss Chance\\s+[\\d.]+%.*")
                    || value.isEmpty() || value.equalsIgnoreCase("miss") || value.equalsIgnoreCase("nothing")
                    || !value.matches("(?i).*\\b(?:hit|miss)(?:\\s+·\\s+Chance\\s+[\\d.]+%)?$");
            return new Modern(m.group(1), Double.parseDouble(m.group(3)), mf, pl, value, main,
                    value.matches("(?i).*\\bhit(?:\\s+·\\s+Chance\\s+[\\d.]+%)?$"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The rest of a roll's line, sent as a [Debug] line of its own. The server wraps a long
     * line between two fields, so the default's "Odds 1/333 · 1 item odds (hover)" arrives
     * on its own after the roll it belongs to. Returns what it adds to that roll's outcome
     * ("" when it is all figures), or null for a line that is a roll or a header itself.
     */
    public static String tail(String line) {
        if (line == null || !line.startsWith("[Debug] ") || modern(line) != null || mob(line) != null || dragon(line) != null)
            return null;
        StringBuilder out = new StringBuilder();
        for (String part : line.substring("[Debug] ".length()).strip().split("\\s+·\\s+")) {
            if (FIGURE.matcher(part).matches() || STAT.matcher(part).matches()) continue;
            if (!out.isEmpty()) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }

    public static Mob mob(String line) {
        if (line == null) return null;
        var m = MOB.matcher(line);
        if (m.matches()) return new Mob(m.group(1), m.group(2), m.group(3));
        m = OLD_MOB.matcher(line);
        return m.find() ? new Mob(m.group(1), m.group(2), m.group(3)) : null;
    }

    public static Dragon dragon(String line) {
        if (line == null) return null;
        var m = DRAGON.matcher(line);
        if (m.matches()) return new Dragon(m.group(1), m.group(2), m.group(5), m.group(3), m.group(4));
        m = OLD_DRAGON.matcher(line);
        return m.find() ? new Dragon(m.group(1), m.group(2), m.group(3), null, null) : null;
    }

    public static Rare rare(String line) {
        if (line == null) return null;
        var m = RARE.matcher(line);
        if (!m.matches()) return null;
        try { return new Rare(m.group(1), Double.parseDouble(m.group(3)), Double.parseDouble(m.group(2)), m.group(5).equalsIgnoreCase("HIT")); }
        catch (NumberFormatException e) { return null; }
    }

    public static Double normalRoll(String line) {
        if (line == null) return null;
        var m = NORMAL.matcher(line);
        if (!m.matches()) return null;
        try { return Double.valueOf(m.group(1)); }
        catch (NumberFormatException e) { return null; }
    }
}
