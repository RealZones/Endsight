package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Watcher's opening script, and how long it runs before anything actually spawns.
 *
 * Recorded from a real run:
 *
 *   00:00:53  The BLOOD DOOR has been opened!
 *   00:00:55  I have watching you closely since we last met.
 *   00:00:59  So I will decide if you are strong enough.
 *   00:01:01  Let's see how you can handle this.
 *   00:01:03  Let's see how you handle Jolly Pink Giant.   <- the mob arrives here
 *
 * Ten seconds of dialogue at two-second beats, and the line that matters is the last
 * one. The one before it - "Let's see how you can handle this." - is nearly the same
 * sentence two seconds early, which is exactly the kind of near-miss that a loose
 * contains() check turns into a timer that fires early every single run. So the spawn
 * line is matched on naming a mob, and the decoy is rejected explicitly.
 *
 * The ten seconds is not hardcoded as fact. It is one observation, so it is the
 * starting estimate and every run that confirms it refines it.
 */
public final class BloodRoomLogic {

    private BloodRoomLogic() {
    }

    public static final String DOOR = "The BLOOD DOOR has been opened!";

    /** "[BOSS] The Watcher: Let's see how you handle Jolly Pink Giant." */
    private static final Pattern SPAWN = Pattern.compile(
            "^\\[BOSS] The Watcher: Let's see how you handle (.+?)\\.?$");

    /** The same sentence, two seconds early, naming nothing. Not a spawn. */
    private static final String DECOY = "Let's see how you can handle this";

    /** What a single run measured, in milliseconds. */
    private static final List<Long> observed = new ArrayList<>();

    /** The estimate before anything has been measured: one recorded run. */
    public static final long DEFAULT_DELAY_MS = 10_000;

    public static boolean door(String line) {
        return line != null && plain(line).equals(DOOR);
    }

    /**
     * The mob named by the spawn line, or null when this is not it.
     *
     * The decoy is checked first and by exact sentence rather than by "does it contain
     * handle", because the two lines differ by three words.
     */
    public static String spawn(String line) {
        if (line == null) return null;
        String text = plain(line);
        if (text.contains(DECOY)) return null;
        Matcher m = SPAWN.matcher(text);
        if (!m.matches()) return null;
        String mob = m.group(1).trim();
        return mob.isEmpty() || mob.equalsIgnoreCase("this") ? null : mob;
    }

    /** Note how long a run actually took, so the next estimate is better. */
    public static void measured(long millis) {
        if (millis < 1_000 || millis > 60_000) return;      // a reconnect, not a run
        observed.add(millis);
    }

    /**
     * How long to expect, in milliseconds: the median of what has been seen.
     *
     * Median rather than mean because the thing that goes wrong is a single run where
     * the player arrived late or the server lagged, and one bad sample should not drag
     * the estimate for every run after it.
     */
    public static long expected() {
        if (observed.isEmpty()) return DEFAULT_DELAY_MS;
        List<Long> sorted = new ArrayList<>(observed);
        sorted.sort(Long::compare);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    public static int samples() {
        return observed.size();
    }

    public static void forget() {
        observed.clear();
    }

    static String plain(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").trim();
    }
}
