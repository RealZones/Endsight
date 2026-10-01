package com.endsight.slayers;

import java.util.List;

public final class SlayerCostsChecks {
    private static int checks;

    public static void main(String[] args) {
        SlayerCosts costs = new SlayerCosts();
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 1_000), null);
        equal(costs.onLine("SLAYER QUEST STARTED!", 2_000), null);
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 2_100),
                new SlayerCosts.Start("Voidgloom", 5_000_000));
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 2_200), null);
        equal(costs.onLine("SLAYER QUEST STARTED!", 2_201), null);
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 2_202), null);
        equal(costs.onLine("SLAYER QUEST FAILED!", 3_000), null);
        equal(costs.onLine("SLAYER QUEST STARTED!", 4_000), null);
        equal(costs.onLine("Slay 13,750 Combat XP worth of Endermen", 4_100),
                new SlayerCosts.Start("Voidgloom", 15_000_000));

        SlayerCosts.Quote quote = SlayerCosts.quote("Voidgloom Seraph IV", List.of(
                "Start Cost: 4,900,000 coins", "XP Required: 11,000"));
        equal(quote, new SlayerCosts.Quote("Voidgloom", 11_000, 4_900_000));
        costs.learn(quote);
        equal(costs.onLine("SLAYER QUEST STARTED!\nSlay 11,000 Combat XP worth of Endermen", 5_000),
                new SlayerCosts.Start("Voidgloom", 4_900_000));
        equal(SlayerCosts.quote("Riftborn Seraph", List.of(
                "Start Cost: 15,000,000 coins", "XP Required: 13,750")),
                new SlayerCosts.Quote("Voidgloom", 13_750, 15_000_000));
        equal(SlayerCosts.quote("Atoned Horror", List.of(
                "Start Cost: 5,000,000 coins", "XP Required: 13,750")),
                new SlayerCosts.Quote("Revenant", 13_750, 5_000_000));
        equal(SlayerCosts.quote("Unrelated item", List.of(
                "Start Cost: 1 coins", "XP Required: 11,000")), null);
        equal(SlayerCosts.quote("Voidgloom Seraph IV", List.of("XP Required: 11,000")), null);
        equal(costs.onLine("SLAYER QUEST STARTED!\nSlay 99,999 Combat XP worth of Endermen", 6_000),
                new SlayerCosts.Start("Voidgloom", -1));
        equal(costs.onLine("SLAYER QUEST STARTED!", 7_000), null);
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 12_001), null);
        equal(costs.onLine("SLAYER QUEST STARTED!", 13_000), null);
        costs.resetPending();
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 13_100), null);
        equal(costs.onLine("SLAYER QUEST STARTED!", 14_000), null);
        equal(costs.onLine("SLAYER QUEST COMPLETE!", 14_100), null);
        equal(costs.onLine("Slay 11,000 Combat XP worth of Endermen", 14_200), null);
        equal(costs.onLine("SLAYER QUEST STARTED!\nSlay 13,750 Combat XP worth of Zombies", 15_000),
                new SlayerCosts.Start("Revenant", 5_000_000));
        equal(SlayerCosts.historicalCost("Voidgloom", 1_000), 15_000_000_000L);
        equal(SlayerCosts.historicalCost("Revenant", 1_000), 5_000_000_000L);
        equal(SlayerCosts.historicalCost("Golem", 1_000), 0L);
        // The three quest-discount accessories, lore as the tooltip wraps it.
        equal(SlayerCosts.loan(List.of("Reduces the cost of starting a", "quest by 10%.",
                "Place this in your Accessory Bag.")), 10);
        equal(SlayerCosts.loan(List.of("Reduces the cost of starting a", "quest by 20%.")), 20);
        equal(SlayerCosts.loan(List.of("Reduces the cost of starting a quest by 30%.")), 30);
        equal(SlayerCosts.loan(List.of("Against Golden Dragons:", "+10 Damage")), 0);
        equal(SlayerCosts.afterLoan(5_000_000, 30), 3_500_000L);
        equal(SlayerCosts.afterLoan(15_000_000, 20), 12_000_000L);
        equal(SlayerCosts.afterLoan(2_500_000, 10), 2_250_000L);
        equal(SlayerCosts.afterLoan(5_000_000, 0), 5_000_000L);
        // An unknown price stays unknown; a loan cannot make it look paid.
        equal(SlayerCosts.afterLoan(-1, 30), -1L);
        System.out.println(checks + " Slayer cost checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
