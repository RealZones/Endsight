package com.endsight.storage;

import java.util.Map;
import java.util.Properties;

/** Item tiers, fragments and persisted totals must not inflate session value. */
public final class MiningProfitChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        equal(MiningSession.profitValue(Map.of("Amethyst", 6_400L), 0), 1_088_000L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 3 * 6_400L), 1), 83_264_000L);
        equal(MiningSession.profitValue(Map.of(), 2), 160_000_000L);
        equal(MiningSession.profitValue(Map.of("Obsidian", 160L, "End Stone", 100L), 0), 69_000L);
        equal(MiningSession.profitValue(Map.of("Amethyst", -1L), -1), 0L);
        MiningTotals totals = new MiningTotals();
        totals.material("Amethyst", 6_400);
        // A compactor moving raw items between ticks has no net sale-value gain.
        totals.material("Amethyst", -80);
        totals.material("Amethyst", 80);
        equal(totals.materials.get("Amethyst"), 6_400L);
        totals.fragments = 3;
        totals.activeMs = 90_000;
        totals.sold = 80_000_000;
        MiningTotals restored = new MiningTotals();
        restored.load(totals.properties());
        equal(restored.materials, totals.materials);
        equal(restored.fragments, 3L);
        equal(restored.activeMs, 90_000L);
        equal(restored.sold, 80_000_000L);
        equal(MiningSession.profitValue(restored.materials, restored.fragments), 241_088_000L);
        Properties corrupt = new Properties();
        corrupt.setProperty("activeMs", "not a number");
        corrupt.setProperty("fragments", "-1");
        restored.load(corrupt);
        equal(restored.activeMs, 0L);
        equal(restored.fragments, 0L);
        // Resetting the current session must leave the same persisted object intact.
        var field = MiningSession.class.getDeclaredField("lifetime");
        field.setAccessible(true);
        MiningTotals global = (MiningTotals) field.get(null);
        global.material("Amethyst", 6_400);
        global.fragments = 2;
        var reset = MiningSession.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
        equal(global.materials.get("Amethyst"), 6_400L);
        equal(global.fragments, 2L);
        System.out.println(checks + " mining profit checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
