package com.endsight.storage;

import java.util.Map;
import java.util.Properties;

/** Item tiers, fragments and persisted totals must not inflate session value. */
public final class MiningProfitChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        equal(MiningSession.profitValue(Map.of("Amethyst", 6_400L), 0), 1_305_600L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 512_000L), 0), 104_448_000L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 80L), 0), 8_160L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 1L), 0), 50L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 5L), 0), 252L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 6_400L + 80 + 5), 0), 1_314_012L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 79L), 0), 3_982L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 6_399L), 0), 648_622L);
        equal(MiningSession.profitValue(Map.of("Amethyst", 3 * 6_400L), 1), 83_916_800L);
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
        equal(MiningSession.profitValue(restored.materials, restored.fragments), 241_305_600L);
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
        // Exact chat evidence includes decimals on Rough, not just whole-coin sales.
        var onLine = MiningSession.class.getDeclaredMethod("onLine", net.minecraft.network.chat.Component.class);
        onLine.setAccessible(true);
        for (String sale : new String[]{
                "You sold 1x Flawless Amethyst for 104,448,000 coins!",
                "You sold 1x Fine Amethyst for 1,305,600 coins!",
                "You sold 1x Flawed Amethyst for 8,160 coins!",
                "You sold 1x Rough Amethyst for 50.4 coins!"})
            onLine.invoke(null, net.minecraft.network.chat.Component.literal(sale));
        var sold = MiningSession.class.getDeclaredField("soldCoins");
        sold.setAccessible(true);
        equal(sold.get(null), 105_761_810L);
        System.out.println(checks + " mining profit checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
