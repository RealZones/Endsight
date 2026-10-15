package com.endsight.dragons;

import com.endsight.hud.Alerts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Server remaining-kill counts must never become percentages or personal kill estimates. */
public final class ProtectorChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Method read = method("readTabLine", String.class, long.class);
        Method line = method("onLine", String.class);
        Method reset = method("reset");
        Method fresh = method("counterFresh", long.class);
        Field alertsEnabled = Alerts.class.getDeclaredField("enabled");
        alertsEnabled.setAccessible(true);
        alertsEnabled.setBoolean(null, false);
        reset.invoke(null);

        // Tab's number counts down from 200 to zero across all tiers. Changing
        // tier must not add another 200 kills to the HUD.
        int[][] observed = {{1, 200, 0}, {1, 191, 9}, {2, 160, 40},
                {2, 101, 99}, {3, 100, 100}, {3, 65, 135},
                {4, 31, 169}, {4, 1, 199}, {4, 0, 200}, {5, 0, 200}};
        for (int[] pair : observed) {
            yes((boolean) read.invoke(null,
                    "Protector: Rising (Tier " + pair[0] + "/5, " + pair[1] + ")", 1_000L),
                    "rising row rejected");
            eq(number("tier"), pair[0], "wrong stage");
            eq(number("remaining"), pair[1], "remaining value treated as percentage");
            eq(number("killGoal"), 200, "current server rule lost its denominator");
            eq(completed(), pair[2], "wrong total kills");
        }
        yes((boolean) fresh.invoke(null, 5_999L), "fresh server count hidden");
        yes(!(boolean) fresh.invoke(null, 6_000L), "stale server count stayed visible");

        yes((boolean) read.invoke(null, "Protector: Spawned", 2_000L), "spawned tab row rejected");
        eq(number("tier"), 5, "spawned did not reach stage five");
        eq(number("remaining"), 0, "spawned kept the last remaining count");
        eq(number("killGoal"), 200, "spawned did not complete the 200-kill climb");
        eq(completed(), 200, "spawned began an extra stage of kills");
        yes(Protector.protectorActive(), "existing Protector API stopped working");
        line.invoke(null, "The Warden unleashes a shockwave!");
        yes(Protector.wardenUp(), "Warden action did not transform the readout");
        read.invoke(null, "Protector: Spawned", 2_500L);
        yes(Protector.wardenUp(), "generic Protector tab row erased the Warden transformation");
        line.invoke(null, "The Warden has been defeated!");
        yes(!Protector.wardenUp() && !Protector.protectorActive(), "Warden death left a fight active");
        eq(number("killGoal"), -1, "previous fight goal survived death");

        read.invoke(null, "Protector: Resting (195)", 3_000L);
        yes(bool("resting"), "resting status was ignored");
        eq(number("remaining"), 195, "resting countdown lost");
        eq(number("killGoal"), -1, "resting row reused previous cycle progress");
        read.invoke(null, "Protector: Rising (Tier 1/5, 191)", 3_500L);
        yes(!bool("resting"), "rising did not end resting");
        eq(number("killGoal"), 200, "next cycle did not recover its known goal");

        // An incompatible counter must be unknown, never silently clamped or converted.
        read.invoke(null, "Protector: Rising (Tier 3/5, 650)", 4_000L);
        eq(number("remaining"), 650, "unknown-format remaining count changed");
        eq(number("killGoal"), -1, "oversized remaining count became a valid counter");
        eq(completed(), -1, "invalid tab counter became visible progress");
        read.invoke(null, "Protector: Rising (Tier 4/5, 65)", 4_500L);
        eq(number("killGoal"), 200, "valid remaining count was rejected");
        eq(completed(), 135, "stage four added previous tiers to the kill count");
        read.invoke(null, "Protector: Rising (Tier 3/6, 65)", 4_750L);
        eq(number("killGoal"), -1, "different stage maximum invented progress");
        read.invoke(null, "Protector: Rising (Tier 3/5)", 5_000L);
        eq(number("remaining"), -1, "stage-only row reused stale remaining kills");
        eq(number("killGoal"), -1, "stage-only row invented total kills");
        read.invoke(null, "Protector: Rising (Tier 5/5)", 5_250L);
        eq(number("killGoal"), 200, "stage-five row without a counter lost its completed goal");
        eq(completed(), 200, "stage-five row without a counter became an unknown climb");
        yes(!(boolean) read.invoke(null, "Protector: Rising (Tier 6/5, 0)", 5_500L),
                "out-of-range stage accepted");
        yes(!(boolean) read.invoke(null, "Protector: Rising (Tier 3/5, 65%)", 5_500L),
                "percentage interpreted as a kill count");
        yes(!(boolean) read.invoke(null, "[DRAGON] ImFear: Protector: Rising (Tier 4/5, 31)", 5_500L),
                "player name row could spoof the server status");

        line.invoke(null, "[MVP+] ImFear: Endstone Protector has spawned.");
        yes(!bool("up"), "player paste spawned a Protector");
        line.invoke(null, "The Endstone Protector is rising from the ground! (Tier 4/5)");
        eq(number("tier"), 4, "legacy tier announcement ignored");
        read.invoke(null, "Protector: Rising (Tier 4/5, 31)", 6_000L);
        line.invoke(null, "The Endstone Protector has emerged from the ground! (Tier 5/5)");
        line.invoke(null, "Endstone Protector has spawned.");
        yes(bool("up"), "chat spawn did not activate Protector");
        eq(number("remaining"), 0, "chat spawn did not complete known progress");
        eq(number("killGoal"), 200, "last tier announcement erased the known goal");
        line.invoke(null, "The Endstone Protector has been defeated!");
        yes(!Protector.protectorActive(), "Protector death left beacon API active");
        read.invoke(null, "Warden: Spawned", 7_000L);
        yes(Protector.wardenUp(), "explicit Warden sidebar row was ignored");
        reset.invoke(null);
        eq(number("remaining"), -1, "join/disconnect reset kept a lobby count");
        eq(number("killGoal"), -1, "join/disconnect reset kept a lobby goal");
        yes(!Protector.wardenUp() && !Protector.protectorActive(), "reset kept an active boss");

        System.out.println("Protector checks passed: " + checks);
    }

    private static Method method(String name, Class<?>... args) throws Exception {
        Method m = Protector.class.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static Field field(String name) throws Exception {
        Field f = Protector.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static int number(String name) throws Exception { return field(name).getInt(null); }
    private static boolean bool(String name) throws Exception { return field(name).getBoolean(null); }
    private static int completed() throws Exception {
        return (int) method("completedKills", int.class, int.class, int.class).invoke(null,
                number("tier"), number("tierMax"), number("remaining"));
    }

    private static void eq(int actual, int expected, String why) {
        checks++;
        if (actual != expected) throw new AssertionError(why + ": expected " + expected + ", got " + actual);
    }

    private static void yes(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
}
