package com.endsight.slayers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Boss name tags shared by the End and Rift versions of the fight. */
record SeraphBoss(String family, String tier, String health, String maxHealth, int hits, boolean radiating) {
    private static final Pattern NAME = Pattern.compile(
            "(Voidgloom Seraph|Riftborn Seraph)(?: (IV|III|II|I|V))?\\s+"
                    + "(\\d[\\d.,]*[kKmMbB]?)/(\\d[\\d.,]*[kKmMbB]?)\\u2764(?:\\s+(\\d+) Hits?)?");

    static SeraphBoss read(String name) {
        if (name == null) return null;
        Matcher match = NAME.matcher(name);
        if (!match.find()) return null;
        boolean rift = match.group(1).equals("Riftborn Seraph");
        String tier = match.group(2);
        // The recorded T5 calls itself Riftborn Seraph, without a tier in its tag.
        // A missing tier on a Voidgloom tag is not the same boss format.
        if ((!rift && tier == null) || (rift && tier != null && !tier.equals("V"))) return null;
        int hits;
        try {
            hits = match.group(5) == null ? -1 : Integer.parseInt(match.group(5));
        } catch (NumberFormatException invalidHits) {
            return null;
        }
        return new SeraphBoss(rift ? "Riftborn" : "Voidgloom", tier == null ? "V" : tier,
                match.group(3), match.group(4), hits, name.substring(match.end()).contains("Radiation"));
    }

    /** T5's changing label is a separate TextDisplay just above the static mob tag. */
    static SeraphBoss liveDisplay(SeraphBoss body, String text, double dx, double dy, double dz,
                                  boolean ridingBody) {
        if (body == null || !body.family.equals("Riftborn") ||
                !ridingBody && (dx * dx + dz * dz > 0.6 * 0.6 || dy < 1.5 || dy > 4.5)) return null;
        SeraphBoss live = read(text);
        return live != null && live.family.equals(body.family) && live.maxHealth.equals(body.maxHealth)
                ? live : null;
    }

    String label() {
        return family.equals("Riftborn") ? "Riftborn Seraph" : "Seraph " + tier;
    }

    boolean supportsRadiation() {
        return family.equals("Riftborn") || tier.equals("IV");
    }
}
