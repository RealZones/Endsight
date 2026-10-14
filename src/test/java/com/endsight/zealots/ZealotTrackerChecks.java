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
        eq(count("sinceSpecial"), 0, "special kill did not reset dry streak");
        ZealotTracker.recordKill(false);
        line.invoke(null, "[VIP+] Someone: EPIC DROP! Golden Eye");
        eq(count("sinceSpecial"), 1, "quoted drop reset dry streak");
        line.invoke(null, "EPIC DROP! Golden Eye");
        eq(count("sinceSpecial"), 0, "personal Golden Eye did not reset dry streak");
        ZealotTracker.recordKill(false);
        line.invoke(null, "RARE DROP! (Summoning Eye)");
        eq(count("sinceSpecial"), 0, "personal Summoning Eye did not reset dry streak");
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
