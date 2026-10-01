package com.endsight.qol;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SlotLockChecks {
    private static int checks;

    public static void main(String[] args) {
        Map<Integer, Integer> pairs = new LinkedHashMap<>();
        check(SlotLock.togglePair(pairs, 9, 0), "main to hotbar links");
        check(pairs.get(9) == 0 && pairs.get(0) == 9, "both directions stored");

        check(SlotLock.togglePair(pairs, 9, 1), "relink main slot");
        check(!pairs.containsKey(0) && pairs.get(9) == 1 && pairs.get(1) == 9,
                "old hotbar reverse link removed");

        check(SlotLock.togglePair(pairs, 10, 1), "relink hotbar slot");
        check(!pairs.containsKey(9) && pairs.get(10) == 1 && pairs.get(1) == 10,
                "old main reverse link removed");

        check(SlotLock.togglePair(pairs, 1, 10), "same pair toggles off in either direction");
        check(pairs.isEmpty(), "unlink removes both directions");

        check(!SlotLock.togglePair(pairs, 9, 10), "main-to-main rejected");
        check(!SlotLock.togglePair(pairs, 0, 1), "hotbar-to-hotbar rejected");
        check(!SlotLock.togglePair(pairs, 36, 0), "armor slot rejected");
        check(!SlotLock.togglePair(pairs, 40, 0), "offhand slot rejected");
        check(!SlotLock.togglePair(pairs, 0, 0), "same slot rejected");
        check(pairs.isEmpty(), "invalid gestures leave pairs unchanged");

        check(SlotLock.togglePair(pairs, 35, 8), "last main and hotbar slots accepted");
        check(pairs.get(35) == 8 && pairs.get(8) == 35, "boundary pair symmetric");

        pairs.clear();
        Set<Integer> locks = new LinkedHashSet<>();
        check(SlotLock.linkAndLock(pairs, locks, 10, 2), "link gesture accepted");
        check(locks.contains(10) && locks.contains(2), "both endpoints lock with link");
        SlotLock.toggleLock(locks, pairs, 10);
        check(!locks.contains(10) && locks.contains(2), "unlock changes only the chosen slot");
        check(pairs.isEmpty(), "unlocking main removes both tracer directions");

        check(SlotLock.linkAndLock(pairs, locks, 10, 2), "relink after unlock");
        SlotLock.toggleLock(locks, pairs, 2);
        check(!locks.contains(2) && locks.contains(10), "hotbar can be unlocked independently");
        check(pairs.isEmpty(), "unlocking hotbar removes both tracer directions");

        check(!SlotLock.linkAndLock(pairs, locks, 10, 11), "invalid link rejected");
        check(pairs.isEmpty() && locks.contains(10) && !locks.contains(11),
                "invalid link does not create a lock or tracer");
        System.out.println(checks + " Slot Lock checks passed");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
