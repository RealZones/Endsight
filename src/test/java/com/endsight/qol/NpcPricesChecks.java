package com.endsight.qol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sale examples from the server log, plus first-sale pricing. */
public final class NpcPricesChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        equal(NpcPrices.parseSale("§aYou sold 2x Judgement Core for §610,000,000 coins!"),
                new NpcPrices.Sale("Judgement Core", 2, 5_000_000));
        equal(NpcPrices.parseSale("§aYou sold 64x Enchanted Obsidian for §64,096,000 coins!"), null);
        equal(NpcPrices.parseSale("You sold 1x [Lvl 1] Enderman for 50,000,000 coins!"),
                new NpcPrices.Sale("Enderman", 1, 50_000_000));
        equal(NpcPrices.parseSale("You sold 1x [Lvl 1] Golden Dragon for 50,000,000 coins!"), null);
        equal(NpcPrices.parseSale("You sold 3x Judgement Core for 10 coins!"), null);
        equal(NpcPrices.parseAmount("2.5m"), 2_500_000L);
        equal(NpcPrices.parseAmount("1,200"), 1_200L);
        equal(NpcPrices.parseAmount("0"), null);
        equal(NpcPrices.parseAmount("1.2345m"), null);
        equal(NpcPrices.price("Golden Eye"), 50_000_000L);
        equal(NpcPrices.price("Rift Pass"), 50_000_000L);
        equal(NpcPrices.price("Enderman"), 50_000_000L);
        equal(NpcPrices.price("Spicy Wart"), 15_000_000L);
        equal(NpcPrices.priceInfo("Spicy Wart").source(), "Built-in NPC");
        equal(NpcPrices.parseSale("You sold 2x Spicy Wart for 30,000,000 coins!"),
                new NpcPrices.Sale("Spicy Wart", 2, 15_000_000));
        equal(NpcPrices.value("Spicy Wart", 2), 30_000_000L);
        equal(NpcPrices.price("§7[Lvl 1] §6Enderman"), 50_000_000L);
        equal(NpcPrices.price("[lVl 200]   enderman"), 50_000_000L);
        equal(NpcPrices.price("[Lvl 1] Enderman (Epic)"), 0L);
        equal(NpcPrices.price("[Lvl 1] Golden Dragon"), 0L);
        equal(NpcPrices.ambiguousPet("[Lvl 1] Enderman"), false);
        equal(NpcPrices.ambiguousPet("[Lvl 1] Golden Dragon"), true);
        equal(NpcPrices.priceInfo("Enderman").source(), "Built-in NPC");
        equal(NpcPrices.observedPrice("[Lvl 1] Enderman").coins(), 50_000_000L);
        equal(NpcPrices.ZEALOT_ITEMS, List.of("Golden Eye", "Rift Pass", "Summoning Eye", "Warden Catalyst"));
        equal(NpcPrices.ZEALOT_PROFIT_ITEMS.containsAll(List.of("Enderman", "Spicy Wart")), true);
        equal(NpcPrices.knownNames().stream().filter("Enderman"::equals).count(), 1L);
        equal(NpcPrices.priceInfo("Golden Eye").source(), "Default");
        equal(NpcPrices.observedPrice("Rift Pass"), null);
        equal(NpcPrices.parseSale("You sold 1x Summoning Eye for 1,000,000 coins!"),
                new NpcPrices.Sale("Summoning Eye", 1, 1_000_000));
        equal(NpcPrices.observe("You sold 2x Golden Eye for 100,000,000 coins!"), true);
        equal(NpcPrices.priceInfo("Golden Eye").source(), "Learned sale");
        NpcPrices.learning(false);
        equal(NpcPrices.priceInfo("Golden Eye").source(), "Default");
        NpcPrices.learning(true);

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
        equal(NpcPrices.observe("You sold 1x Seraph’s Plating for 25,000,000 coins!"), true);
        equal(NpcPrices.price("Seraph's Plating"), 25_000_000L);
        equal(NpcPrices.price("Seraph Plating"), 25_000_000L);
        equal(NpcPrices.value("Seraph’s Plating", 3), 75_000_000L);

        equal(NpcPrices.observe("You sold 2x [Lvl 1] Enderman for 120,000,000 coins!"), true);
        equal(NpcPrices.price("Enderman"), 60_000_000L);
        equal(NpcPrices.price("[Lvl 200] Enderman"), 60_000_000L);
        equal(NpcPrices.priceInfo("Enderman").source(), "Learned sale");
        NpcPrices.learning(false);
        equal(NpcPrices.price("Enderman"), 50_000_000L);
        NpcPrices.learning(true);

        // Exercise precedence without persisting test overrides into the player's config.
        Field overrides = NpcPrices.class.getDeclaredField("custom");
        overrides.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Long> custom = (Map<String, Long>) overrides.get(null);
        custom.put("enderman", 75_000_000L);
        custom.put("spicy wart", 123_456L);
        try {
            equal(NpcPrices.price("[Lvl 1] Enderman"), 75_000_000L);
            equal(NpcPrices.priceInfo("Enderman").source(), "Custom");
            equal(NpcPrices.customPrice("[Lvl 1] Enderman"), 75_000_000L);
            equal(NpcPrices.value("Enderman", 2), 150_000_000L);
            equal(NpcPrices.price("Spicy Wart"), 123_456L);
            equal(NpcPrices.priceInfo("Spicy Wart").source(), "Custom");
        } finally {
            custom.remove("enderman");
            custom.remove("spicy wart");
        }
        long beforeSale = NpcPrices.revision();
        NpcPrices.observe("You sold 1x Spicy Wart for 15,000,000 coins!");
        equal(NpcPrices.revision() > beforeSale, true);
        long beforeToggle = NpcPrices.revision();
        NpcPrices.learning(false);
        equal(NpcPrices.revision() > beforeToggle, true);
        long unchanged = NpcPrices.revision();
        NpcPrices.learning(false);
        equal(NpcPrices.revision(), unchanged);
        NpcPrices.learning(true);

        // Normalization repeats at HUD frequency; the same result is reused while
        // arbitrary names cannot grow the text-only cache without limit.
        Method normalize = NpcPrices.class.getDeclaredMethod("normalized", String.class);
        normalize.setAccessible(true);
        Object normalized = normalize.invoke(null, "§6[Lvl 200] Enderman");
        equal(normalized == normalize.invoke(null, "§6[Lvl 200] Enderman"), true);
        for (int i = 0; i < 4096; i++) normalize.invoke(null, "Unpriced item " + i);
        Field cachedNames = NpcPrices.class.getDeclaredField("normalizedNames");
        cachedNames.setAccessible(true);
        equal(((Map<?, ?>) cachedNames.get(null)).size() <= 2048, true);
        equal(NpcPrices.price("[Lvl 200] Enderman"), 60_000_000L);
        System.out.println("NpcPricesChecks: " + checks + " checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
