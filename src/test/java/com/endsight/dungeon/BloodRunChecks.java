package com.endsight.dungeon;

/** The completion banner's server time is the full-run result, not local wall time. */
public final class BloodRunChecks {
    public static void main(String[] args) {
        check(Long.valueOf(515_000).equals(BloodRush.runMillis("☠ Defeated Necron in 08m 35s")));
        check(BloodRush.runMillis("☠ Defeated Necron in 08m 75s") == null);
        check(BloodRush.runMillis("[BOSS] Necron: Defeated in 08m 35s") == null);
        System.out.println("3 full-run timing checks passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Full-run banner was parsed incorrectly");
    }
}
