package com.endsight.dragons;

public final class EggRespawnTimingChecks {
    private static int checks;
    public static void main(String[] args) {
        long delay = EggRespawnTiming.announcedDelay("§5The Dragon Egg is respawning in 10 seconds.");
        check(delay == 10_000, "13:28:43 announcement to 13:28:53 spawn is ten seconds");
        check(EggRespawnTiming.countdown(delay - 6_000).equals("4s"), "four seconds remain when the old timer said ready");
        check(EggRespawnTiming.progress(delay - 6_000, delay) == .6f, "bar uses announced interval");
        check(EggRespawnTiming.announcedDelay("The Dragon Egg is respawning in 6 seconds.") == 6_000, "older intervals remain supported");
        check(EggRespawnTiming.announcedDelay("The Dragon Egg is respawning in 10 minutes.") == 600_000, "units are respected");
        check(EggRespawnTiming.announcedDelay("[VIP+] Someone: The Dragon Egg is respawning in 10 seconds.") == -1, "quoted player chat is not a timer");
        check(EggRespawnTiming.announcedDelay("The Dragon Egg is respawning in soon.") == -1, "malformed announcement does not change timer");
        check(EggRespawnTiming.announcedDelay("The Dragon Egg is respawning in 999999999999999 minutes.") == -1, "overflow is rejected");
        check(EggRespawnTiming.countdown(10_000).equals("10s"), "exact start does not show eleven seconds");
        check(EggRespawnTiming.countdown(1).equals("1s"), "fraction rounds up");
        check(EggRespawnTiming.countdown(60_001).equals("1m01s"), "long waits stay compact");
        check(EggRespawnTiming.progress(-100, delay) == 1f, "bar clamps after deadline");
        check(EggRespawnTiming.progress(delay + 100, delay) == 0f, "bar clamps before start");
        System.out.println(checks + " egg respawn timing checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
