package com.endsight.dungeon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Higher Or Lower": kill the blazes in order of health. The chest shows which way.
 *
 * All of this is the orderable part, kept away from the world so it can be tested:
 * read a health out of a name tag, sort, and say which blaze is next. The client half
 * only has to find the blazes and draw on one of them.
 *
 * The nametag format is the server's usual mob label, the same shape every other mob
 * on it uses:
 *
 *     [Lv120] Blaze 1.4M/1.4M❤
 *
 * so a health is "number, optional k/m/b, slash, the same again, heart". Ordering is on
 * the CURRENT health rather than the maximum: the puzzle is about the values on the
 * tags in front of you, and a blaze part-way damaged still shows where it belongs.
 */
public final class HigherOrLowerLogic {

    private HigherOrLowerLogic() {
    }

    /** "1.4M/1.4M❤" anywhere in the tag; the trailing heart is what makes it a health. */
    private static final Pattern HEALTH = Pattern.compile(
            "(\\d[\\d.,]*)\\s*([kKmMbB]?)\\s*/\\s*(\\d[\\d.,]*)\\s*([kKmMbB]?)\\s*\\u2764");

    /** Which end of the order to kill first. */
    public enum Order { LOWEST_FIRST, HIGHEST_FIRST }

    /** One blaze: what its tag says, and the health that orders it. */
    public record Blaze(long health, long max, String label) {
    }

    /** Read a blaze's health out of its name tag, or null when the tag is not one. */
    public static Blaze read(String rawName) {
        if (rawName == null) return null;
        String text = rawName.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
        if (!text.toLowerCase(Locale.ROOT).contains("blaze")) return null;
        Matcher m = HEALTH.matcher(text);
        if (!m.find()) return null;
        Long health = scaled(m.group(1), m.group(2));
        Long max = scaled(m.group(3), m.group(4));
        if (health == null || max == null) return null;
        return new Blaze(health, max, text);
    }

    private static Long scaled(String digits, String suffix) {
        long scale = switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            default -> 1L;
        };
        try {
            return new BigDecimal(digits.replace(",", "")).multiply(BigDecimal.valueOf(scale)).longValue();
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Read the order off whatever the room says.
     *
     * The puzzle line is "The chest shows which way", so the direction is written
     * somewhere in the room rather than fixed. Any nearby text naming one end decides
     * it; nothing recognised returns null and the caller keeps whatever it had, which
     * is better than flipping the order on a stray word mid-room.
     */
    public static Order order(String text) {
        if (text == null) return null;
        String t = text.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        boolean low = t.contains("lowest") || t.contains("weakest") || t.contains("ascending")
                || t.contains("low to high") || t.contains("smallest");
        boolean high = t.contains("highest") || t.contains("strongest") || t.contains("descending")
                || t.contains("high to low") || t.contains("largest");
        if (low == high) return null;             // both or neither: not an answer
        return low ? Order.LOWEST_FIRST : Order.HIGHEST_FIRST;
    }

    /** The blazes in the order they should be killed. */
    public static List<Blaze> sorted(List<Blaze> blazes, Order order) {
        List<Blaze> out = new ArrayList<>(blazes == null ? List.of() : blazes);
        Comparator<Blaze> byHealth = Comparator.comparingLong(Blaze::health);
        out.sort(order == Order.HIGHEST_FIRST ? byHealth.reversed() : byHealth);
        return out;
    }

    /** The one to shoot now, or null when there is nothing left. */
    public static Blaze next(List<Blaze> blazes, Order order) {
        List<Blaze> ordered = sorted(blazes, order);
        return ordered.isEmpty() ? null : ordered.get(0);
    }
}
