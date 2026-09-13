package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

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
 */
public final class LootFilter {

    private LootFilter() {
    }

    private static boolean enabled = false;
    private static double showUnder = 2.5;
    private static boolean bothEnds = true;

    /** A bare roll: the number and nothing after it. The arrow of a real drop keeps it from matching. */
    private static final Pattern ROLL = Pattern.compile("^\\s*loot number:\\s*([\\d.]+)\\s*$");

    public static Module module() {
        return new Module("qol.lootfilter", "Loot Number Filter",
                "Hides loot numbers except the close calls at either end.", "Quality of Life",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Show under",
                                "Loot numbers under this stay in chat; the rest are hidden. Drops always show.",
                                0, 50, 0.5, () -> showUnder, v -> showUnder = v, ""),
                        new Setting.Toggle("Both ends",
                                "Also keep rolls the same distance from 100 - a 99.98 is as rare as a 0.02.",
                                () -> bothEnds, v -> bothEnds = v)));
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!enabled || overlay) return true;
            Matcher m = ROLL.matcher(Zealots.strip(message.getString()));
            if (!m.find()) return true;
            try {
                double roll = Double.parseDouble(m.group(1));
                return roll < showUnder || (bothEnds && roll >= 100 - showUnder);
            } catch (NumberFormatException e) {
                return true;
            }
        });
    }
}
