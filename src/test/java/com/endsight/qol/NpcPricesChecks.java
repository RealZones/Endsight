package com.endsight.qol;

import java.util.Objects;

/** Sale examples from the server log, plus first-sale pricing. */
public final class NpcPricesChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(NpcPrices.parseSale("§aYou sold 2x Judgement Core for §610,000,000 coins!"),
                new NpcPrices.Sale("Judgement Core", 2, 5_000_000));
        equal(NpcPrices.parseSale("§aYou sold 64x Enchanted Obsidian for §64,096,000 coins!"), null);
        equal(NpcPrices.parseSale("You sold 1x [Lvl 1] Enderman for 10,000,000 coins!"), null);
        equal(NpcPrices.parseSale("You sold 3x Judgement Core for 10 coins!"), null);
        equal(NpcPrices.parseAmount("2.5m"), 2_500_000L);
        equal(NpcPrices.parseAmount("1,200"), 1_200L);
        equal(NpcPrices.parseAmount("0"), null);
        equal(NpcPrices.parseAmount("1.2345m"), null);

        String first = "You sold 2x Judgement Core for 10,000,000 coins!";
        equal(NpcPrices.observe(first), true);
        equal(NpcPrices.price("Judgement Core"), 5_000_000L);
        equal(NpcPrices.observe(first), false);
        equal(NpcPrices.value("Judgement Core", 3), 15_000_000L);
        equal(NpcPrices.observe("You sold 1x Judgement Core for 6,000,000 coins!"), true);
        equal(NpcPrices.price("Judgement Core"), 6_000_000L);
        equal(NpcPrices.observe("You sold 1x Judgement Core for 6,000,000 coins!"), false);
        NpcPrices.learning(false);
        equal(NpcPrices.price("Judgement Core"), 0L);
        NpcPrices.learning(true);
        equal(NpcPrices.price("Judgement Core"), 6_000_000L);
        equal(NpcPrices.price("Enchanted Obsidian"), 0L);
        System.out.println("NpcPricesChecks: " + checks + " checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
