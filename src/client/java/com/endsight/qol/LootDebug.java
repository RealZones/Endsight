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
    // /debug lets players choose which fields appear. The roll and source are
    // structural; MF, PL and Luck are optional, and their exact order can vary.
    private static final Pattern MODERN = Pattern.compile("^\\[Debug\\]\\s+(.+?)\\s+·\\s+(Roll|Table)\\s+([\\d.]+)/100(?:\\s+·\\s+(.+))?$");

    public record Mob(String id, String magicFind, String petLuck) { }
    public record Dragon(String name, String rank, String damage, String score, String eyes) { }
    public record Rare(String name, double chance, double rolled, boolean hit) { }
    public record Modern(String mob, double roll, String magicFind, String petLuck, String result, boolean main, boolean hit) { }

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
                else {
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
