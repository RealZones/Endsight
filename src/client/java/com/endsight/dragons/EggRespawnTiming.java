package com.endsight.dragons;

import java.util.regex.Pattern;

/** Timing from server announcements, kept separate for recorded-cycle regression checks. */
final class EggRespawnTiming {
    static final long FALLBACK_MS = 10_000;
    private static final Pattern WAIT = Pattern.compile(
            "(?:The )?Dragon Egg is respawning in (\\d+) (seconds?|minutes?)\\.", Pattern.CASE_INSENSITIVE);

    private EggRespawnTiming() { }

    static long announcedDelay(String line) {
        if (line == null) return -1;
        var match = WAIT.matcher(line.replaceAll("§.", "").trim());
        if (!match.matches()) return -1;
        try {
            long amount = Long.parseLong(match.group(1));
            return Math.multiplyExact(amount, match.group(2).toLowerCase(java.util.Locale.ROOT).startsWith("minute")
                    ? 60_000L : 1_000L);
        } catch (NumberFormatException | ArithmeticException ex) {
            return -1;
        }
    }

    static float progress(long remaining, long duration) {
        return duration <= 0 ? 1f : Math.clamp(1f - remaining / (float) duration, 0f, 1f);
    }

    static String countdown(long remaining) {
        long seconds = Math.max(0, remaining / 1000 + (remaining % 1000 > 0 ? 1 : 0));
        return seconds < 60 ? seconds + "s" : seconds / 60 + "m" + String.format("%02d", seconds % 60) + "s";
    }
}
