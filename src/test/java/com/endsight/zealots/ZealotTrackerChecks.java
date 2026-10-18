package com.endsight.zealots;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.endsight.qol.NpcPrices;

/** Exercises the real chat receiver so debug companions and player pastes cannot inflate a session. */
public final class ZealotTrackerChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Method line = ZealotTracker.class.getDeclaredMethod("onLine", String.class);
        line.setAccessible(true);
        Method reset = ZealotTracker.class.getDeclaredMethod("resetSession");
        reset.setAccessible(true);
        reset.invoke(null);

        line.invoke(null, "rare roll: Warden Catalyst (1 in 5)");
        eq(count("catalysts"), 0, "rare-roll description counted before its announcement");
        yes(number("sessionStart") == 0, "debug-only line started the session clock");
        line.invoke(null, "EPIC DROP! Warden Catalyst");
        eq(count("catalysts"), 1, "personal catalyst announcement was ignored");
        yes(number("sessionStart") > 0 && number("lastActivity") > 0,
                "catalyst drop did not start the session clock");
        reset.invoke(null);

        // A drop is also described by these observed debug and compact-roll formats.
        line.invoke(null, "EPIC DROP! Golden Eye");
        line.invoke(null, "rare roll: Warden Catalyst (1 in 5)");
        line.invoke(null, "EPIC DROP! Warden Catalyst");
        line.invoke(null, "▍ Special Zealot 90.49 → Golden Eye · ✔ Warden Catalyst · Rift Pass 80.37");
        eq(count("golden"), 1, "golden-eye behavior changed");
        eq(count("catalysts"), 1, "debug companions counted a catalyst twice");
        line.invoke(null, "[DRAGON] IMainDragSim: EPIC DROP! Warden Catalyst");
        line.invoke(null, "[VIP+] JohnTanking: EPIC DROP! Warden Catalyst");
        line.invoke(null, "base 20.00000000% → effective 20.00000000% [0.00000000, 20.00000000) Warden Catalyst");
        line.invoke(null, "loot number: 73.80309");
        line.invoke(null, "result: nothing");
        eq(count("catalysts"), 1, "a player paste or debug table counted as a drop");

        // A later real announcement is a new drop even when its text is identical.
        line.invoke(null, "EPIC DROP! Warden Catalyst");
        line.invoke(null, "RARE DROP! (Summoning Eye) (✯ 174%)");
        eq(count("catalysts"), 2, "a second personal drop was lost");
        eq(count("eyes"), 1, "summoning-eye behavior changed");

        line.invoke(null, "LEGENDARY DROP! Rift Pass");
        line.invoke(null, "rare roll Rift Pass (1 in 5, 20%) rolled 3.1 hit");
        line.invoke(null, "▍ Special Zealot 90.49 → Golden Eye · ✔ Rift Pass");
        line.invoke(null, "[DRAGON] Someone: LEGENDARY DROP! Rift Pass");
        line.invoke(null, "BONUS! You received 1x Rift Pass!");
        eq(count("riftPasses"), 1, "Rift Pass announcement was lost or debug/bonus companions inflated it");
        line.invoke(null, "LEGENDARY DROP! Rift Pass");
        eq(count("riftPasses"), 2, "a separate Rift Pass announcement was lost");
        yes(ZealotTracker.profitValue(new int[]{3, 2, 0, 0}) == 250_000_000L,
                "Golden Eyes and Rift Passes did not use the 50M defaults");
        yes(!ZealotTracker.missingPrices(new int[]{3, 2, 0, 0}), "zero unpriced drops made profit incomplete");
        yes(ZealotTracker.missingPrices(new int[]{0, 0, 1, 0}), "unpriced Summoning Eyes were silently valued");
        yes(ZealotTracker.missingPrices(new int[]{0, 0, 0, 1}), "unpriced Catalysts were silently valued");
        // The same sale/custom provider feeds the HUD after counts were already recorded.
        Field overrides = NpcPrices.class.getDeclaredField("custom");
        overrides.setAccessible(true);
        @SuppressWarnings("unchecked") var custom = (java.util.Map<String, Long>) overrides.get(null);
        custom.put("golden eye", 65_000_000L);
        custom.put("warden catalyst", 8_000_000L);
        yes(ZealotTracker.profitValue(new int[]{3, 2, 0, 1}) == 303_000_000L,
                "editing prices did not revalue existing session drops");
        yes(!ZealotTracker.missingPrices(new int[]{0, 0, 0, 1}), "a custom Catalyst value still appeared missing");
        custom.clear();

        reset.invoke(null);
        eq(count("kills"), 0, "kills survived session reset");
        eq(count("eyes"), 0, "eyes survived session reset");
        eq(count("golden"), 0, "golden eyes survived session reset");
        eq(count("catalysts"), 0, "catalysts survived session reset");
        eq(count("riftPasses"), 0, "Rift Passes survived session reset");
        yes(number("sessionStart") == 0 && number("lastActivity") == 0,
                "session clock survived reset");

        ZealotTracker.recordKill(false);
        ZealotTracker.recordKill(false);
        eq(count("sinceSpecial"), 2, "ordinary kills did not advance dry streak");
        ZealotTracker.recordKill(true);
        eq(count("sinceSpecial"), 2, "special death reset or advanced the spawn streak");
        ZealotTracker.recordKill(false);
        line.invoke(null, "[VIP+] Someone: EPIC DROP! Golden Eye");
        eq(count("sinceSpecial"), 3, "quoted drop reset dry streak");
        line.invoke(null, "EPIC DROP! Golden Eye");
        eq(count("sinceSpecial"), 3, "Golden Eye payout reset the spawn streak");
        line.invoke(null, "RARE DROP! (Summoning Eye)");
        eq(count("sinceSpecial"), 3, "ordinary Summoning Eye reset the spawn streak");
        eq(count("golden"), 1, "Golden Eye payout stopped being counted");
        eq(count("eyes"), 1, "Summoning Eye payout stopped being counted");

        // Exact successful personal roll from 2026-10-04-2.log.gz:1873. Another
        // player pasted a matching roll on October 12; an anchored parser rejects it.
        Method spawn = ZealotTracker.class.getDeclaredMethod("recordSpecialSpawn", String.class, long.class);
        spawn.setAccessible(true);
        String personalSpawn = "§a▶ §7rare roll §fSpecial Zealot §8(1 in 383, 0.26139%) §7rolled §f0.13772 §aHIT";
        yes((boolean) spawn.invoke(null, personalSpawn, 1_000L), "recorded personal Special spawn was not recognized");
        eq(count("sinceSpecial"), 0, "personal spawn did not reset the streak");
        ZealotTracker.recordKill(false);
        yes((boolean) spawn.invoke(null, personalSpawn, 1_100L), "duplicate personal signal was not consumed");
        eq(count("sinceSpecial"), 1, "duplicate spawn signal erased a later ordinary kill");
        yes(!(boolean) spawn.invoke(null, "[MVP] Spirings: ▶ rare roll Special Zealot (1 in 383, 0.26139%) rolled 0.13772 HIT", 1_200L),
                "another player's pasted spawn reset the streak");
        yes(!(boolean) spawn.invoke(null, "rare roll Special Zealot (1 in 383, 0.26139%) rolled 3.13772 MISS", 1_200L),
                "a missed spawn roll reset the streak");
        yes(!(boolean) spawn.invoke(null, "Special Zealot has spawned nearby!", 1_200L),
                "an unverified nearby-spawn message was treated as personal ownership");
        eq(count("sinceSpecial"), 1, "nonpersonal spawn text changed the streak");
        yes(!(boolean) spawn.invoke(null, "[Roll] Special Zealot 1.04826866 < 0.22093921% (1 in 453) miss", 1_500L),
                "new missed roll reset streak");
        yes(!(boolean) spawn.invoke(null, "[MVP+] Someone: [Roll] Special Zealot 0.1 < 0.22093921% (1 in 453) HIT", 1_500L),
                "new player paste reset streak");
        String modernSpawn = "[Roll] Special Zealot 0.1 < 0.22093921% (1 in 453) HIT";
        yes((boolean) spawn.invoke(null, modernSpawn, 1_600L), "new successful spawn not recognized");
        eq(count("sinceSpecial"), 0, "new personal spawn did not reset streak");
        ZealotTracker.recordKill(false);
        yes((boolean) spawn.invoke(null, modernSpawn, 1_700L), "new repeated signal not consumed");
        eq(count("sinceSpecial"), 1, "new duplicate erased later kills");
        // The real network receiver also handles hidden detailed lines. Later eye
        // payouts and the Special's own death preserve kills since its spawn.
        line.invoke(null, "rare roll Special Zealot (1 in 383, 0.26139%) rolled 0.10010 HIT");
        eq(count("sinceSpecial"), 0, "chat receiver ignored the successful spawn flag");
        ZealotTracker.recordKill(false);
        line.invoke(null, "EPIC DROP! Golden Eye");
        line.invoke(null, "RARE DROP! (Summoning Eye)");
        ZealotTracker.recordKill(true);
        eq(count("sinceSpecial"), 1, "later Special death or eye payout reset the spawn streak");
        ZealotTracker.recordKill(false);
        reset.invoke(null);
        eq(count("sinceSpecial"), 0, "dry streak survived session reset");

        // Real drop lines from the October logs: only personal announcements count.
        line.invoke(null, "RNGESUS DROP! [Lvl 1] Enderman");
        line.invoke(null, "EPIC DROP! Spicy Wart");
        eq(count("endermanPets"), 1, "recorded Enderman pet announcement was lost");
        eq(count("spicyWarts"), 1, "recorded Spicy Wart announcement was lost");
        line.invoke(null, "loot number: 0.091 → [Lvl 1] Enderman");
        line.invoke(null, "▍ Zealot 0.091 → [Lvl 1] Enderman · Special Zealot 94.32");
        line.invoke(null, "▍ Zealot 1.55 → Spicy Wart");
        line.invoke(null, "[DRAGON] Someone: EPIC DROP! Spicy Wart");
        line.invoke(null, "Instance > [VIP+] Someone: RNGESUS DROP! [Lvl 1] Enderman");
        line.invoke(null, "You sold 1x Spicy Wart for 15,000,000 coins!");
        eq(count("endermanPets"), 1, "pet debug/player companions were double counted");
        eq(count("spicyWarts"), 1, "wart debug/player/sale companions were counted");
        line.invoke(null, "EPIC DROP! Spicy Wart x3");
        eq(count("spicyWarts"), 4, "drop quantity was ignored");
        yes(ZealotTracker.extraDrop("§d§lRNGESUS DROP! §r§6§7[Lvl 1] §6Enderman").item().equals("Enderman"),
                "actual coloured pet line did not parse");
        yes(ZealotTracker.extraDrop("EPIC DROP! 2x Spicy Wart").count() == 2, "prefix quantity ignored");
        yes(ZealotTracker.extraDrop("EPIC DROP! Spicy Wart x0") == null, "zero drop accepted");
        yes(ZealotTracker.extraDrop("EPIC DROP! Spicy Wart x999999999999") == null, "overflow accepted");
        yes(ZealotTracker.extraDrop("EPIC DROP! Enderman Cortex Rewriter") == null, "different Enderman item matched");
        yes(ZealotTracker.profitValue(new int[]{0, 0, 0, 0, 2, 3}) == 145_000_000,
                "hidden items did not contribute at their NPC prices");
        yes(!ZealotTracker.missingPrices(new int[]{0, 0, 0, 0, 2, 3}), "hidden NPC drops appeared unpriced");
        var breakdown = ZealotTracker.profitBreakdown(new int[]{0, 0, 0, 0, 2, 3});
        yes(breakdown.stream().anyMatch(v -> v.contains("Enderman") && v.contains("100.0m")), "tooltip lost pet contribution");
        yes(breakdown.stream().anyMatch(v -> v.contains("Spicy Wart") && v.contains("45.0m")), "tooltip lost wart contribution");
        yes(breakdown.getLast().contains("145.0m"), "tooltip total differs from HUD");
        yes(breakdown.stream().anyMatch(v -> v.contains("/each")), "tooltip lost each-item price");
        yes(breakdown.stream().noneMatch(v -> v.contains("Built-in NPC") || v.contains("Learned sale") || v.contains("Custom")),
                "profit tooltip exposed price provenance");
        // Only the first four items stay on the HUD. These additions must still
        // reach profit through personal announcements or a personal Zealot result.
        line.invoke(null, "EPIC DROP! Warped Stone");
        line.invoke(null, "RNGESUS DROP! [Lvl 1] Wolf");
        line.invoke(null, "RNGESUS DROP! InfiniEye™");
        line.invoke(null, "§8▍ §fZealot §a0.116 §8→ §aNull Ovoid §8· §7Special Zealot §837.47");
        eq(count("warpedStones"), 1, "Warped Stone announcement was ignored");
        eq(count("wolfPets"), 1, "Wolf announcement was ignored");
        eq(count("infiniEyes"), 1, "InfiniEye announcement was ignored");
        eq(count("nullOvoids"), 1, "personal compact Null Ovoid result was ignored");
        line.invoke(null, "loot number: 0.11660 → Null Ovoid");
        line.invoke(null, "[DRAGON] Someone: EPIC DROP! Warped Stone");
        line.invoke(null, "[VIP+] Someone: RNGESUS DROP! [Lvl 1] Wolf");
        line.invoke(null, "[DRAGON] Someone: RNGESUS DROP! InfiniEye™");
        line.invoke(null, "RARE DROP! Null Ovoid");
        line.invoke(null, "▍ Voidgloom 0.116 → Null Ovoid");
        eq(count("warpedStones"), 1, "pasted Warped Stone drop was counted");
        eq(count("wolfPets"), 1, "pasted Wolf drop was counted");
        eq(count("infiniEyes"), 1, "pasted InfiniEye drop was counted");
        eq(count("nullOvoids"), 1, "debug or Slayer ovoid was counted as a Zealot drop");
        yes(ZealotTracker.profitValue(new int[]{0, 0, 0, 0, 0, 0, 1, 1, 1, 1}) == 106_000_000,
                "new hidden drops did not use Eggy's editable default prices");
        var newBreakdown = ZealotTracker.profitBreakdown(new int[]{0, 0, 0, 0, 0, 0, 1, 1, 1, 1});
        yes(newBreakdown.stream().anyMatch(v -> v.contains("Warped Stone")), "tooltip lost Warped Stone");
        yes(newBreakdown.stream().anyMatch(v -> v.contains("InfiniEye")), "tooltip lost InfiniEye");
        var partial = ZealotTracker.profitBreakdown(new int[]{0, 0, 1, 0, 1, 0});
        yes(partial.getLast().contains("unpriced"), "tooltip silently omitted unpriced contribution");
        yes(NpcPrices.ZEALOT_ITEMS.size() == 4, "hidden items expanded the compact item list");
        int[] bounds = {10, 20, 200, 115};
        yes(ZealotTracker.profitHovered(bounds, 1, 15, 100), "profit row not hoverable");
        yes(ZealotTracker.profitHovered(bounds, 1, 15, 121), "profit/hour row not hoverable");
        yes(!ZealotTracker.profitHovered(bounds, 1, 15, 122), "time row showed profit tooltip");
        yes(!ZealotTracker.profitHovered(bounds, 1, 15, 99), "item row showed profit tooltip");
        yes(!ZealotTracker.profitHovered(bounds, 1, 210, 100), "outside HUD showed tooltip");
        yes(ZealotTracker.profitHovered(new int[]{10, 20, 300, 173}, 1.5f, 15, 140), "scaled hover missed row");
        yes(!ZealotTracker.profitHovered(null, 1, 15, 100), "hidden HUD showed tooltip");
        reset.invoke(null);
        eq(count("endermanPets"), 0, "hidden pet count survived session reset");
        eq(count("spicyWarts"), 0, "hidden wart count survived session reset");
        eq(count("warpedStones"), 0, "Warped Stone count survived session reset");
        eq(count("wolfPets"), 0, "Wolf count survived session reset");
        eq(count("infiniEyes"), 0, "InfiniEye count survived session reset");
        eq(count("nullOvoids"), 0, "Null Ovoid count survived session reset");

        System.out.println("Zealot tracker checks passed: " + checks);
    }

    private static int count(String name) throws Exception {
        return (int) number(name);
    }

    private static long number(String name) throws Exception {
        Field field = ZealotTracker.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(null)).longValue();
    }

    private static void eq(int actual, int expected, String reason) {
        checks++;
        if (actual != expected) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }

    private static void yes(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
}
