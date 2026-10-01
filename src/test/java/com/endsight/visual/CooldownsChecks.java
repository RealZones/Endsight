package com.endsight.visual;

import java.util.List;

public final class CooldownsChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(Cooldowns.riftwalkerAfterHit(0, 100), 260);
        equal(Cooldowns.riftwalkerAfterHit(260, 180), 260);
        equal(Cooldowns.riftwalkerAfterHit(260, 260), 420);
        check(Cooldowns.riftwalkerPiece("Fierce Riftwalker Chestplate", "chestplate"));
        check(Cooldowns.riftwalkerPiece("Riftwalker Helmet", "helmet"));
        check(!Cooldowns.riftwalkerPiece("Ender Chestplate", "chestplate"));
        // The Warden Helmet's Chameleon makes it any set's helmet; tooltip wording as seen in game.
        check(Cooldowns.anySetHelmet("Renowned Warden Helmet ✪✪✪✪✪", List.of()));
        check(Cooldowns.anySetHelmet("Some Future Helmet", List.of("Chameleon",
                "Counts as the helmet of any armor", "set: wear it with the other three",
                "pieces to unlock that set's Full", "Set Bonus.")));
        check(!Cooldowns.anySetHelmet("Riftwalker Chestplate", List.of("Full Set Bonus: Riftwalk")));
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
