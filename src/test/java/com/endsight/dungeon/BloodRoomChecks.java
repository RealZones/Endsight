package com.endsight.dungeon;

public final class BloodRoomChecks {
    private static int checks;

    public static void main(String[] args) {
        // -- the real script, line for line, as the logs recorded it ------------
        check(BloodRoomLogic.door("§cThe §4§lBLOOD DOOR §chas been opened!"),
                "the door line is recognised through its colour codes");
        check(!BloodRoomLogic.door("[BOSS] The Watcher: Ah, you've finally arrived."),
                "dialogue is not the door");

        check(BloodRoomLogic.spawn("[BOSS] The Watcher: Ah, you've finally arrived.") == null,
                "the greeting is not a spawn");
        check(BloodRoomLogic.spawn("[BOSS] The Watcher: So I will decide if you are strong enough.") == null,
                "nor is the warning");

        // the decoy: nearly the same sentence, two seconds early, names nothing
        check(BloodRoomLogic.spawn("[BOSS] The Watcher: Let's see how you can handle this.") == null,
                "\"how you CAN handle this\" is the decoy, not the spawn");

        check("Jolly Pink Giant".equals(
                        BloodRoomLogic.spawn("[BOSS] The Watcher: Let's see how you handle Jolly Pink Giant.")),
                "the real spawn line gives up the mob's name");
        check("Wither Guard".equals(
                        BloodRoomLogic.spawn("§c[BOSS] The Watcher§r§f: §rLet's see how you handle Wither Guard.")),
                "and it still does with colour codes in the way");
        check(BloodRoomLogic.spawn("[BOSS] The Watcher: That will be enough for now.") == null,
                "the closing line is not a spawn");
        check(BloodRoomLogic.spawn("[DRAGON] someone: Let's see how you handle Jolly Pink Giant.") == null,
                "a player quoting the line does not start the timer");

        // -- the estimate learns -----------------------------------------------
        BloodRoomLogic.forget();
        check(BloodRoomLogic.expected() == BloodRoomLogic.DEFAULT_DELAY_MS,
                "with nothing measured it uses the one recorded run");
        check(BloodRoomLogic.samples() == 0, "and admits it has no samples");

        BloodRoomLogic.measured(10_000);
        BloodRoomLogic.measured(10_100);
        BloodRoomLogic.measured(9_900);
        check(BloodRoomLogic.expected() == 10_000, "three close runs settle on the middle one");

        // one bad run - the player reconnected, the server hung - must not move it much
        BloodRoomLogic.measured(25_000);
        check(BloodRoomLogic.expected() <= 10_100, "an outlier does not drag the median");

        BloodRoomLogic.measured(500);
        BloodRoomLogic.measured(90_000);
        check(BloodRoomLogic.samples() == 4, "impossible gaps are rejected outright");

        BloodRoomLogic.forget();
        System.out.println(checks + " blood room checks passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
