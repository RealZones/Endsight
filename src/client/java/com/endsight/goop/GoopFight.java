package com.endsight.goop;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Goop cycle, read from the encounter's own narration: which form is up, what comes
 * next and when, and what the end of a fight paid you.
 *
 * Most of the fight moves on damage - Slime to Colony, Corruptor to Devourer - and has
 * nothing to count down. Three waits are the server's own clocks, measured in Fear's
 * recording of 2026-11-01 (thirteen fights, millisecond stamps):
 * <ul>
 * <li>A squeeze to the Rift Slime landing: 31.0-31.5 s. The boss bar's "The rift stirs ·
 *     30s" is the same wait started a second late. "The ground is shaking..." comes 15.5 s
 *     before the landing.</li>
 * <li>"??? ...under you..." to the Corruptor rising: 11.5-12.4 s, and once 9.8 s. "It
 *     drags you under..." is 4.0 s before the rise in all thirteen, the 9.8 s one too, so the
 *     count re-anchors on it, and once more on "It spits you back out.", 1.0 s before.</li>
 * <li>The Devourer dying ("It stops moving.") to The Disturbed: 6.3-7.2 s, and 2.0 s after
 *     "The rift swallows everything.". The same drag and spit lines come during that wait
 *     in long fights, so they only re-anchor the Corruptor's.</li>
 * </ul>
 * Each line is matched whole, so a player repeating one in chat ("Name: It stops
 * moving.") never moves the fight along.
 */
public final class GoopFight {

    public enum Phase {
        NONE(""), STIRRING("Rift Slime"), SLIME("Rift Slime"), COLONY("Rift Colony"), UNDER("Rift Corruptor"),
        CORRUPTOR("Rift Corruptor"), DEVOURER("Rift Devourer"), DEVOURED("The Disturbed"), DISTURBED("The Disturbed");

        /** The form that is up, or for a wait, the one coming. */
        public final String form;

        Phase(String form) {
            this.form = form;
        }

        public boolean waiting() {
            return this == STIRRING || this == UNDER || this == DEVOURED;
        }
    }

    static final long SQUEEZE_TO_SLIME = 31_300, SHAKING_TO_SLIME = 15_500;
    static final long UNDER_TO_RISE = 12_000, DRAG_TO_RISE = 4_000, SPIT_TO_RISE = 1_000;
    static final long STOP_TO_DISTURBED = 7_000, SWALLOW_TO_DISTURBED = 2_000;
    /** The box, the pay line and the Disturbed's drops all land in the tick the rift falls silent; this is slack. */
    static final long PAYOUT_MS = 5_000;

    private static final Pattern PLACER = Pattern.compile(
            "^(?:✦ )?(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) squeezes the goop into the altar\\.$");
    /** "Your Damage: 310,821,751.82 (0.7%, Position #3)" in the end box. */
    private static final Pattern YOUR_DAMAGE = Pattern.compile("^Your Damage: ([\\d,.]+) \\(([\\d.]+)%, Position #(\\d+)\\)$");
    private static final Pattern UNPAID = Pattern.compile("^You did ([\\d.]+)% of the damage\\. The top \\d+.* are paid\\.$");
    /** "+1 Crypt Cache, 14 Rift Goo and 2 Gelatinous Membranes (top three)", the reason missing when paid otherwise. */
    private static final Pattern PAY = Pattern.compile("^\\+(.+?)(?: \\(([^)]*)\\))?$");
    private static final Pattern COUNTED = Pattern.compile("^([\\d,]+) ([A-Za-z' -]+)$");
    private static final Pattern LISTED = Pattern.compile("^(?:([\\d,]+)x )?(.+?)(?: x([\\d,]+))?$");

    /** One finished fight you were in. */
    public record Result(boolean placed, boolean paid, int place, double share, double damage, Map<String, Integer> drops) {
    }

    private Phase phase = Phase.NONE;
    private long due = -1, fightAt = -1, lastAt = -1;
    private long finishedAt = -1;
    private boolean placedThis;
    /** Goops other people placed since your last one; -1 until you have placed one. */
    private int sinceMine = -1;

    // The fight that just ended, until its payout window closes.
    private long endedAt = -1;
    private boolean endedPlaced, paid, inBox, dropList;
    private int place = -1;
    private double share = -1, damage;
    private Map<String, Integer> drops = new LinkedHashMap<>();

    /** One line of chat, colour codes already stripped. Returns the fight it closed, if any. */
    public Result line(String text, long now, String me) {
        String line = text.strip();
        Result closed = poll(now);
        // The result box is a fallback if the final narration was missed. Keep the
        // first timestamp: the narration and result must not restart the placement countdown.
        if (line.equals("✦ THE RIFT SLEEPS") && finishedAt < 0
                && (phase == Phase.NONE || phase == Phase.DISTURBED)) finishedAt = now;
        if (endedAt >= 0 && payout(text, line)) {
            lastAt = now;
            return closed;
        }
        Matcher m = PLACER.matcher(line);
        if (m.matches()) {
            Result previous = close();
            if (closed == null) closed = previous;
            placedThis = m.group(1).equalsIgnoreCase(me);
            // Parties take turns at the altar; this is how long since your turn.
            if (placedThis) sinceMine = 0;
            else if (sinceMine >= 0) sinceMine++;
            enter(Phase.STIRRING, now + SQUEEZE_TO_SLIME);
            fightAt = -1;
        } else if (line.equals("The ground begins to shake...")) {
            if (phase != Phase.STIRRING) enter(Phase.STIRRING, now + SQUEEZE_TO_SLIME);
        } else if (line.equals("The ground is shaking...")) {
            enter(Phase.STIRRING, now + SHAKING_TO_SLIME);
        } else if (line.equals("The rift throws you skyward!")) {
            if (phase == Phase.DEVOURED) enter(Phase.DISTURBED, -1);
            else if (phase == Phase.STIRRING || phase == Phase.NONE) {
                enter(Phase.SLIME, -1);
                fightAt = now;
            }
        } else if (line.equals("It bursts into pieces! Kill every last one of them.")) {
            enter(Phase.COLONY, -1);
        } else if (line.equals("It pulls itself back together...")) {
            enter(Phase.SLIME, -1);
        } else if (line.equals("??? ...under you...")) {
            enter(Phase.UNDER, now + UNDER_TO_RISE);
        } else if (line.equals("It drags you under...")) {
            if (phase == Phase.UNDER) due = now + DRAG_TO_RISE;
        } else if (line.equals("It spits you back out.")) {
            if (phase == Phase.UNDER) due = now + SPIT_TO_RISE;
        } else if (line.equals("The corruption begins. The sky is not empty any more.")) {
            enter(Phase.CORRUPTOR, -1);
        } else if (line.equals("The island itself starts to come apart.")) {
            enter(Phase.DEVOURER, -1);
        } else if (line.equals("It stops moving.")) {
            enter(Phase.DEVOURED, now + STOP_TO_DISTURBED);
        } else if (line.equals("The rift swallows everything.")) {
            if (phase == Phase.DEVOURED) due = now + SWALLOW_TO_DISTURBED;
        } else if (line.equals("The rift falls silent.")) {
            if (finishedAt < 0) finishedAt = now;
            Result previous = close();
            if (closed == null) closed = previous;
            endedAt = now;
            endedPlaced = placedThis;
            placedThis = false;
            enter(Phase.NONE, -1);
            fightAt = -1;
        } else if (!speaker(line, now)) {
            return closed;
        }
        lastAt = now;
        return closed;
    }

    /**
     * One of the boss's own lines, "Rift Slime Whole again. Disappointing.". Joined
     * mid-fight, they are what names the form - but only from nothing: the Slime talks
     * while it is underground, and that must not cut the Corruptor's count short.
     */
    private boolean speaker(String line, long now) {
        for (Phase p : new Phase[]{Phase.SLIME, Phase.COLONY, Phase.CORRUPTOR, Phase.DEVOURER, Phase.DISTURBED}) {
            if (!line.startsWith(p.form + " ")) continue;
            if (phase == Phase.NONE) {
                enter(p, -1);
                fightAt = now;
            }
            return true;
        }
        return false;
    }

    /** The end box, the pay line and the Disturbed's drops, in the seconds after the rift falls silent. */
    private boolean payout(String text, String line) {
        if (line.equals("✦ THE RIFT SLEEPS")) {
            inBox = true;
            return true;
        }
        Matcher m;
        if (inBox && (m = YOUR_DAMAGE.matcher(line)).matches()) {
            damage = number(m.group(1));
            share = number(m.group(2));
            place = Integer.parseInt(m.group(3));
            return true;
        }
        if ((m = UNPAID.matcher(line)).matches()) {
            share = number(m.group(1));
            return true;
        }
        if (line.equals("THE DISTURBED DROPPED")) {
            dropList = true;
            return true;
        }
        // The list is indented under its header; the first line that is not ends it.
        if (dropList && text.startsWith(" ") && !line.isEmpty()) {
            Matcher d = LISTED.matcher(line);
            if (d.matches()) {
                int count = d.group(1) != null ? (int) number(d.group(1)) : d.group(3) != null ? (int) number(d.group(3)) : 1;
                drops.merge(d.group(2), Math.max(1, count), Integer::sum);
            }
            return true;
        }
        dropList = false;
        Map<String, Integer> pay = pay(line);
        if (pay == null) return false;
        paid = true;
        pay.forEach((item, count) -> drops.merge(item, count, Integer::sum));
        return true;
    }

    /**
     * The Goop's pay line as items, or null. Other "+" lines land in the same second -
     * "+5 ☘ Mining Fortune (2x)", "+120 Bits (Carrot Buff, 3 left)" - so it must carry the
     * Goop's own Rift Goo, and every part must be a count and a plain name.
     */
    static Map<String, Integer> pay(String line) {
        Matcher m = PAY.matcher(line);
        if (!m.matches()) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : m.group(1).split(",\\s*|\\s+and\\s+")) {
            Matcher c = COUNTED.matcher(part.strip());
            if (!c.matches()) return null;
            int count = (int) number(c.group(1));
            out.merge(singular(c.group(2).strip(), count), count, Integer::sum);
        }
        return out.containsKey("Rift Goo") ? out : null;
    }

    /** "2 Gelatinous Membranes" is the item Gelatinous Membrane; one of anything is already singular. */
    private static String singular(String name, int count) {
        return count != 1 && name.endsWith("s") && !name.endsWith("ss") ? name.substring(0, name.length() - 1) : name;
    }

    private void enter(Phase next, long nextDue) {
        if (next != Phase.NONE) finishedAt = -1;
        phase = next;
        due = nextDue;
    }

    /** The finished fight once its payout window has passed; null before that, or if you were not in it. */
    public Result poll(long now) {
        return endedAt >= 0 && now - endedAt > PAYOUT_MS ? close() : null;
    }

    private Result close() {
        if (endedAt < 0) return null;
        // Took part: the box names your damage, you were paid, it was your goop, or it dropped
        // you something - Fear's logs have a Corrupted Core from the Disturbed with no box at all.
        boolean in = place > 0 || share >= 0 || paid || endedPlaced || !drops.isEmpty();
        Result r = in ? new Result(endedPlaced, paid, place, share, damage, Map.copyOf(drops)) : null;
        endedAt = -1;
        endedPlaced = paid = inBox = dropList = false;
        place = -1;
        share = -1;
        damage = 0;
        drops = new LinkedHashMap<>();
        return r;
    }

    /** A world change: the fight on screen is not this one any more. */
    public void reset() {
        enter(Phase.NONE, -1);
        fightAt = lastAt = -1;
        finishedAt = -1;
        placedThis = false;
        close();
    }

    public int sinceMine() {
        return sinceMine;
    }

    public Phase phase() {
        return phase;
    }

    /** Final kill or result receipt, independent of the payout collection window. */
    public long finishedAt() {
        return finishedAt;
    }

    /** Milliseconds until the wait ends, or -1 when the current step waits on damage. */
    public long remaining(long now) {
        return due < 0 ? -1 : Math.max(0, due - now);
    }

    /** How long the Slime has been up, or -1 before it lands. */
    public long fightTime(long now) {
        return fightAt < 0 ? -1 : now - fightAt;
    }

    /** When anything of the Goop's was last read, to keep the tracker on screen while you are at the altar. */
    public long lastSeen() {
        return lastAt;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String seconds(long ms) {
        return ms >= 10_000 ? (ms + 999) / 1000 + "s" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
