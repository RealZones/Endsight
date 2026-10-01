package com.endsight.visual;

public final class CooldownsChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(Cooldowns.riftwalkerAfterHit(0, 100), 260);
        equal(Cooldowns.riftwalkerAfterHit(260, 180), 260);
        equal(Cooldowns.riftwalkerAfterHit(260, 260), 420);
        check(Cooldowns.riftwalkerPiece("Fierce Riftwalker Chestplate", "chestplate"));
        check(Cooldowns.riftwalkerPiece("Riftwalker Helmet", "helmet"));
        check(!Cooldowns.riftwalkerPiece("Ender Chestplate", "chestplate"));
        System.out.println(checks + " Riftwalker cooldown checks passed");
    }

    private static void equal(long actual, long expected) {
        check(actual == expected);
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Riftwalker cooldown check " + checks + " failed");
    }
}
