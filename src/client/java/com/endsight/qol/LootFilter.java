package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides the loot numbers that do not matter.
 *
 * With /debug on, every kill prints "loot number: 93.96443" - one roll from 0 to 100
 * against the loot table, and the rare things sit at the bottom of it: the Null Atom
 * and the Enderman pet in the logs both landed under 0.11. So the line is only worth
 * reading when the roll is low, and the other hundred a minute are chat scrolling by.
 * Rolls at or above the number are dropped before they reach chat; the ones under it
 * stay, so a close call is still there to see. The top end is kept too, by default:
 * the roll is uniform (in a thousand logged rolls, fourteen were under 1 and thirteen
 * over 99), so a 99.98 is exactly as rare as a 0.02 - it just did not drop anything,
 * which is the whole joke of posting one.
 *
 * A roll that actually dropped something reads "loot number: 0.10838 → Null Atom",
 * and never matches here - it is the line the drop trackers read, and it is the one
 * line you would not want hidden anyway.
 *
 * /debug detailed is judged in {@link LootRoll}, a whole kill at a time. Hiding just
 * its loot number line here left the table, the result and the rare roll under it,
 * so the filter looked like it did nothing in that mode.
 *
 * A custom debug message is read through the message itself ({@link DebugMessage}),
 * one roll at a time: a hit or a drop always shows, anything else only when its roll
 * is close. Before that, no fixed pattern could find the roll in anyone's own wording,
 * and every line went through.
 *
 * The server's own [Debug] line is judged the same way, one roll at a time:
 * "[Debug] Roll 25.50295/100 · MF 317.93152 · PL 71.3 · miss · Miss Chance 99.67524% · Odds 1/1".
 * It used to be left to {@link LootRoll}, which judged it a kill at a time - five rolls
 * a zealot, so one close roll brought the other four back with it - and which only knew
 * the line with the mob in front, so with that field off nothing read it at all.
 */
public final class LootFilter {

    private LootFilter() {
    }

    private static boolean enabled = false;
    private static double showUnder = 2.5;
    private static boolean bothEnds = true;

    /**
     * A message of several lines can come as several chat messages. Those are held for
     * the rest of the tick, until the whole roll is in, then shown or dropped together -
     * judged one by one, the line with the roll would go and the lines around it stay.
     */
    private static final List<Component> held = new ArrayList<>();
    private static int heldLine = -1;
    private static Double heldRoll;
    private static String heldResult;
    /** Released lines go back through the chat events; this lets them past. */
    private static boolean sending;
    /**
     * Whether the last [Debug] roll showed. A long one wraps onto a second [Debug] line,
     * "Odds 1/333 · 1 item odds (hover)", which has no roll of its own to judge: alone it
     * always passed, and every hidden roll left one behind.
     */
    private static Boolean follow;

    /** A bare roll: the number and nothing after it. The arrow of a real drop keeps it from matching. */
    private static final Pattern ROLL = Pattern.compile("^\\s*loot number:\\s*([\\d.]+)\\s*$");

    public static Module module() {
        return new Module("qol.lootfilter", "Loot Number Filter",
                "Hides loot numbers except the close calls.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Show under",
                                "Only loot numbers under this show. Drops always show.",
                                0, 50, 0.5, () -> showUnder, v -> showUnder = v, ""),
                        new Setting.Toggle("Both ends",
                                "Also show rolls just as close to 100.",
                                () -> bothEnds, v -> bothEnds = v)));
    }

    /** On by itself, or by Compact Loot Roll, which shows close calls only as well. */
    static boolean on() {
        return enabled || LootRoll.on();
    }

    /** The one rule, for every number: low enough, or as close to 100 when both ends count. */
    static boolean shows(double roll) {
        return roll < showUnder || (bothEnds && roll >= 100 - showUnder);
    }

    static Double roll(String line) {
        Double modern = LootDebug.normalRoll(line.trim());
        if (modern != null) return modern;
        Matcher m = ROLL.matcher(line);
        if (!m.find()) return null;
        try { return Double.valueOf(m.group(1)); }
        catch (NumberFormatException e) { return null; }
    }

    /** A custom message's roll: shown if it came to something or was close. A line with no roll on it is not judged. */
    static boolean shows(Double roll, String result) {
        return roll == null || DebugMessage.happened(result) || shows(roll);
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            // A dragon's detailed block has a bare loot number too; LootRoll judges it with the rest.
            if (!on() || overlay || sending || LootRoll.busy()) return true;
            String line = Zealots.strip(message.getString());
            DebugMessage.Read own = DebugMessage.read(line);
            if (own != null) return own.complete() ? shows(own.roll(), own.result()) : hold(message, own);
            return judge(line.strip());
        });
        // Every line of one roll arrives in the same tick; one still held at its end is let go.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!held.isEmpty()) release();
        });
    }

    /**
     * A server [Debug] roll and the line it wraps onto, or an older bare loot number.
     * With Compact Loot Roll on, the [Debug] lines are its to fold and judge, a kill at a time.
     */
    static boolean judge(String line) {
        LootDebug.Modern roll = LootRoll.on() ? null : LootDebug.modern(line);
        if (roll != null) {
            follow = shows(roll.roll(), roll.outcome());
            return follow;
        }
        String tail = follow == null ? null : LootDebug.tail(line);
        if (tail != null) return follow || DebugMessage.happened(tail);
        follow = null;
        Double value = roll(line);
        return value == null || shows(value);
    }

    private static boolean hold(Component message, DebugMessage.Read read) {
        if (read.line() <= heldLine) release();     // the next roll started: the last one is all in
        held.add(message);
        heldLine = read.line();
        if (heldRoll == null) heldRoll = read.roll();
        if (heldResult == null) heldResult = read.result();
        if (read.line() >= DebugMessage.lastLine()) release();
        return false;
    }

    private static void release() {
        List<Component> lines = List.copyOf(held);
        boolean show = shows(heldRoll, heldResult);
        held.clear();
        heldLine = -1;
        heldRoll = null;
        heldResult = null;
        Minecraft mc = Minecraft.getInstance();
        if (!show || mc.player == null) return;
        sending = true;
        try {
            for (Component c : lines) mc.player.sendSystemMessage(c);
        } finally {
            sending = false;
        }
    }
}
