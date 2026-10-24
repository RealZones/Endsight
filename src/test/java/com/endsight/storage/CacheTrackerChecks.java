package com.endsight.storage;

import com.endsight.qol.Drops;
import com.endsight.qol.Rarity;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Private Cache result blocks from the recording, with neighboring unrelated sources. */
public final class CacheTrackerChecks {
    private static int checks;
    private static final String PLACED = "§5☠ §dYou set down a Crypt Cache. §7It opens in 5 seconds.";
    private static final String ROLL = "§5☠ §dThe Crypt Cache rolled like §fChaotic Dragon§d! §7Loot number: §f62.300";
    private static final String HEADER = "§5☠ §dCrypt Cache";
    private static void yes(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void reward(CacheLedger ledger, String raw, long at) {
        ledger.message(ROLL, at); ledger.message(HEADER, at + 10); ledger.message(raw, at + 11);
    }
    public static void main(String[] args) throws Exception {
        CacheLedger ledger = new CacheLedger();
        ledger.message(PLACED, 1_000);
        yes(ledger.opened(false) == 0, "Placing is not opening");
        ledger.message(ROLL, 6_000); ledger.message(HEADER, 6_010);
        ledger.message("§7  §9Dragon Scale", 6_020);
        yes(ledger.opened(false) == 1 && ledger.counts(false).drops.get("Dragon Scale") == 1, "Recorded Chaotic Dragon Cache has one Scale and one opening");
        yes(ledger.rolledMob().equals("Chaotic Dragon"), "Result family is available without treating it as a mob kill");
        yes(ledger.counts(false).colours.get("Dragon Scale") == 0x5555FF, "Preserve source item colour");
        ledger.message("RARE DROP! Summoning Eye", 6_021);
        ledger.message("§7  §6Giant's Core", 6_022);
        yes(!ledger.counts(false).drops.containsKey("Summoning Eye") && !ledger.counts(false).drops.containsKey("Giant's Core"), "A normal mob award ends the Cache loot block");

        ledger = new CacheLedger();
        ledger.message("[DRAGON] Someone: The Crypt Cache rolled like Chaotic Dragon! Loot number: 62.300", 1_000);
        ledger.message(HEADER, 1_001); ledger.message("§7  §6Giant's Core", 1_002);
        yes(ledger.opened(false) == 0 && ledger.counts(false).drops.isEmpty(), "Player chat and nearby Caches cannot create an opening");
        ledger.message(ROLL, 2_000); ledger.message(HEADER, 2_001);
        ledger.message("[VIP+] Someone: hello", 2_002);
        ledger.message("§7  §52x Brand New Reward", 2_003);
        ledger.message("§7  §aNull Atom x3", 2_004);
        yes(ledger.counts(false).drops.get("Brand New Reward") == 2 && ledger.counts(false).drops.get("Null Atom") == 3, "Unknown rewards and quantities count despite interleaved player chat");
        ledger.message("§5§lTHE DISTURBED DROPPED", 2_005); ledger.message("§7  §6Necron's Handle", 2_006);
        yes(!ledger.counts(false).drops.containsKey("Necron's Handle"), "Another loot source cannot leak into Cache rewards");
        reward(ledger, "§7  Nothing.", 3_000);
        yes(ledger.opened(false) == 2 && !ledger.counts(false).drops.containsKey("Nothing."), "An empty result is still an opened Cache");

        ledger = new CacheLedger();
        ledger.message(ROLL, 1_000); ledger.message(HEADER, 1_010); ledger.message("  Too Late", 4_011);
        yes(ledger.counts(false).drops.isEmpty(), "Expired blocks fail closed");
        ledger.message(ROLL, 5_000); ledger.resetSource(); ledger.message(HEADER, 5_010); ledger.message("  Other World", 5_020);
        yes(ledger.counts(false).drops.isEmpty(), "World changes disarm pending loot");
        ledger.message(ROLL, 6_000); ledger.message(ROLL, 6_001);
        ledger.message(HEADER, 6_010); ledger.message("  Item", 6_011);
        ledger.message(HEADER, 6_020); ledger.message("  Item", 6_021);
        yes(ledger.opened(false) == 4 && ledger.counts(false).drops.get("Item") == 2, "Fast repeated openings are not collapsed by text dedupe");
        ledger.message(HEADER, 6_030); ledger.message("  Not Another Reward", 6_031);
        yes(!ledger.counts(false).drops.containsKey("Not Another Reward"), "Each result authorizes exactly one loot block");

        ledger = new CacheLedger();
        reward(ledger, "§7  §7[Lvl 1] §5Ender Dragon", 1_000);
        reward(ledger, "§7  §7[Lvl 1] §6Ender Dragon", 2_000);
        yes(ledger.counts(false).drops.size() == 2, "Pet rarities stay separate");
        var saved = new Gson().toJson(ledger.counts(true));
        CacheLedger restored = new CacheLedger(); restored.restore(new Gson().fromJson(saved, CacheLedger.Counts.class));
        yes(restored.opened(true) == 2 && restored.opened(false) == 0 && restored.counts(true).drops.size() == 2, "Saved totals restore without becoming new session drops");
        reward(restored, "  Dragon Scale", 3_000); restored.resetSession();
        yes(restored.opened(false) == 0 && restored.opened(true) == 3, "Reset session preserves all-time counts");
        CacheLedger multiline = new CacheLedger();
        multiline.message(ROLL + "\n" + HEADER + "\n§7  §92x Dragon Scale\n§7  §6Unknown Relic", 1_000);
        yes(multiline.opened(false) == 1 && multiline.counts(false).drops.size() == 2, "Multi-line packets are parsed as one result block");

        // The existing tracker rule source can be supplied without a Fabric game.
        Field rules = Drops.class.getDeclaredField("rules"); rules.setAccessible(true);
        Class<?> rule = Class.forName("com.endsight.qol.Drops$Rule");
        var constructor = rule.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        rules.set(null, List.of(constructor.newInstance("Preferred", "preferred", 3, 0), constructor.newInstance("Second", "second", 3, 1)));
        Field rarities = Rarity.class.getDeclaredField("cache"); rarities.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Integer> colours = (Map<String,Integer>)rarities.get(null);
        var counts = new CacheLedger.Counts(); counts.opened = 10;
        counts.drops.put("Dragon Scale", 5); counts.drops.put("Second", 8); counts.drops.put("Preferred", 1);
        counts.colours.putAll(Map.of("Dragon Scale", 0x5555FF, "Second", 0xFFAA00, "Preferred", 0xFFAA00));
        colours.putAll(counts.colours);
        yes(CacheTracker.ordered(counts).stream().map(Map.Entry::getKey).toList().equals(List.of("Preferred", "Second", "Dragon Scale")), "Editable tier order wins over count and common rewards remain visible");
        yes(CacheTracker.rows(counts, true).getFirst().label().equals("Opened"), "Opening counter precedes drops");
        yes(CacheTracker.rows(counts, true).stream().anyMatch(row -> row.label().contains("Dragon Scale")), "No boss-specific hidden item list removes Scale");
        var visibility = new CacheVisibility();
        visibility.observe(true, 1_000); visibility.result(1_001);
        yes(!visibility.visible(1_002), "Nearby vaults and results alone cannot show the HUD");
        visibility.placed(2_000);
        yes(visibility.visible(2_000), "Private placement shows the HUD immediately");
        visibility.observe(true, 2_050); visibility.observe(true, 4_000); visibility.observe(true, 7_000);
        yes(visibility.visible(7_000), "Own vault keeps the HUD visible through its opening");
        visibility.observe(false, 7_050);
        yes(visibility.visible(9_999) && !visibility.visible(10_001), "HUD hides three seconds after the last sight of the own vault");
        visibility.observe(true, 8_000); visibility.result(8_001);
        yes(!visibility.visible(10_001), "Another vault at the same spot cannot revive the completed opening");
        visibility.placed(20_000); visibility.result(25_000);
        yes(visibility.visible(29_999) && !visibility.visible(30_001), "Missing placement target uses a bounded post-result grace period");
        visibility.placed(40_000);
        yes(!visibility.visible(55_001), "Missing result and block cannot leave the HUD stuck forever");
        visibility.placed(60_000); visibility.reset();
        yes(!visibility.visible(60_001), "World changes clear HUD visibility without clearing counts");
        var spoof = new CacheLedger();
        yes(!spoof.message("[VIP+] Someone: You set down a Crypt Cache. It opens in 5 seconds.", 1_000).placed(), "Player chat cannot arm HUD placement");
        yes(spoof.message(PLACED, 1_001).placed(), "Private placement emits the HUD trigger");
        var styled = new CacheLedger();
        var pet = net.minecraft.network.chat.Component.literal("  [Lvl 1] ").withColor(0xAAAAAA)
                .append(net.minecraft.network.chat.Component.literal("Ender Dragon").withColor(0xFFAA00));
        reward(styled, CacheTracker.formatted(pet), 1_000);
        yes(styled.counts(false).drops.containsKey("[Lvl 1] Ender Dragon (Legendary)"), "Native styled component colours preserve pet rarity");
        System.out.println(checks + " Cache ownership, source, quantity, history and row-order checks passed");
    }
}
