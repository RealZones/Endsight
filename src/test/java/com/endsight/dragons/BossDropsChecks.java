package com.endsight.dragons;

import com.endsight.qol.Drops;
import com.endsight.qol.NpcPrices;
import com.endsight.slayers.SlayerCostsChecks;
import com.endsight.ui.Setting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class BossDropsChecks {
    private static int checks;
    private static final String LEGACY = "Enderman (legacy)", SCHEMA = "# slayer-family-v2";

    public static void main(String[] args) throws Exception {
        SlayerCostsChecks.main(args);
        read(List.of("kill\tRevenant\t125", "kill\tVoidgloom\t12", "kill\tGolden Dragon\t8",
                "drop\tRevenant\tScythe Blade\t3", "drop\tVoidgloom\tJudgement Core\t2",
                "tier\tJudgement Core\t3"));
        Map<?, ?> loaded = map("loaded");
        equal(value(loaded.get("Revenant"), "kills"), 125);
        equal(value(loaded.get("Revenant"), "historicalCost"), 625_000_000L);
        equal(value(loaded.get(LEGACY), "historicalCost"), 180_000_000L);
        equal(value(loaded.get(LEGACY), "incompleteTime"), true);
        equal(loaded.containsKey("Voidgloom"), false);
        equal(loaded.containsKey("Riftborn"), false);
        equal(value(loaded.get("Golden Dragon"), "kills"), 8);
        equal(BossDrops.dragonKills(null, true), 8);
        equal(BossDrops.dragonKills("Golden", true), 8);
        equal(BossDrops.dragonKills(null, false), 0);
        equal(value(loaded.get("Golden Dragon"), "historicalCost"), 0L);
        equal(((Map<?, ?>) value(loaded.get(LEGACY), "drops")).get("Judgement Core"), 2);
        equal(((Map<?, ?>) value(loaded.get("Revenant"), "drops")).get("Scythe Blade"), 3);
        equal(call("costText", new Class<?>[]{loaded.get(LEGACY).getClass()}, loaded.get(LEGACY)), "~-180m");

        List<String> saved = saved();
        equal(saved.contains("kill\tRevenant\t125"), true);
        equal(saved.contains(SCHEMA), true);
        equal(saved.contains("drop\t" + LEGACY + "\tJudgement Core\t2"), true);
        equal(saved.contains("cost\t" + LEGACY + "\t0\t0\t0\t180000000"), true);
        read(saved);
        equal(value(map("loaded").get(LEGACY), "historicalCost"), 180_000_000L);
        equal(saved(), saved);
        Object increment = call("of", new Class<?>[]{Map.class, String.class}, map("unsaved"), LEGACY);
        set(increment, "starts", 2);
        set(increment, "spawnCost", 10_000_000L);
        set(increment, "kills", 1);
        saved = saved();
        equal(saved.contains("kill\t" + LEGACY + "\t13"), true);
        equal(saved.contains("cost\t" + LEGACY + "\t2\t10000000\t0\t180000000"), true);
        read(saved);
        equal(value(map("loaded").get(LEGACY), "historicalCost"), 180_000_000L);
        equal(value(map("loaded").get(LEGACY), "spawnCost"), 10_000_000L);

        // Streaks. An old save has none, so both start unknown and count up from install.
        setStatic("sinceSuperior", 0);
        setStatic("sincePet", 0);
        setStatic("superiorKnown", false);
        setStatic("petKnown", false);
        read(List.of("kill\tSuperior Dragon\t18"));
        equal(BossDrops.superiorKnown(), false);
        BossDrops.countTowardStreaks("Young Dragon");
        BossDrops.countTowardStreaks("Golden Dragon");   // no Superior roll, still a pet chance
        equal(BossDrops.sinceSuperior(), 1);
        equal(BossDrops.sincePet(), 2);
        BossDrops.countTowardStreaks("Superior Dragon");
        equal(BossDrops.sinceSuperior(), 0);
        equal(BossDrops.superiorKnown(), true);
        equal(BossDrops.sincePet(), 3);
        equal(BossDrops.petKnown(), false);
        saved = saved();
        equal(saved.contains("streak\tsuperior\t0\t1"), true);
        equal(saved.contains("streak\tpet\t3\t0"), true);
        setStatic("sinceSuperior", 99);
        setStatic("superiorKnown", false);
        read(saved);
        equal(BossDrops.sinceSuperior(), 0);
        equal(BossDrops.superiorKnown(), true);
        equal(BossDrops.sincePet(), 3);
        equal(BossDrops.petKnown(), false);
        // An unreleased build wrote "unknown" as -1.
        read(List.of("streak\tpet\t-1"));
        equal(BossDrops.sincePet(), 0);
        equal(BossDrops.petKnown(), false);
        // The pet at any level or rarity resets "since pet"; its fragments do not.
        equal(BossDrops.isDragonPet("[Lvl 1] Ender Dragon (Epic)"), true);
        equal(BossDrops.isDragonPet("[Lvl 100] Ender Dragon (Legendary)"), true);
        equal(BossDrops.isDragonPet("Ender Dragon Fragment"), false);
        equal(BossDrops.isDragonPet("Dragon Claw"), false);

        read(List.of("cost\tVoidgloom\t3\t30000000\t1\t180000000", "kill\tVoidgloom\t14",
                "drop\tVoidgloom\tJudgement Core\t2"));
        Object total = map("loaded").get(LEGACY);
        equal(value(total, "kills"), 14);
        equal(value(total, "historicalCost"), 180_000_000L);
        equal(value(total, "spawnCost"), 30_000_000L);
        equal(call("costText", new Class<?>[]{total.getClass()}, total), "~-210m + ?");
        read(saved());
        equal(value(map("loaded").get(LEGACY), "historicalCost"), 180_000_000L);

        read(List.of("kill\tVoidgloom\t100", "cost\tVoidgloom\t0\t0\t0\t0"));
        equal(value(map("loaded").get(LEGACY), "historicalCost"), 0L);
        read(List.of("kill\tEnderman\t5", "kill\tZombie\t2"));
        equal(value(map("loaded").get(LEGACY), "kills"), 5);
        equal(value(map("loaded").get(LEGACY), "historicalCost"), 75_000_000L);
        equal(value(map("loaded").get("Revenant"), "historicalCost"), 10_000_000L);
        equal(call("compactCoins", new Class<?>[]{long.class}, 15_000_000_000L), "15b");
        equal(BossDrops.rewardQuantity(1, 4), 4);
        equal(BossDrops.rewardQuantity(1, 0), 1);
        equal(BossDrops.rewardQuantity(0, -3), 0);
        equal(call("rewardItem", new Class<?>[]{String.class}, "Revenant"), "Revenant Viscera");
        equal(call("rewardItem", new Class<?>[]{String.class}, "Voidgloom"), "Null Ovoid");
        // A three-minute boss still owns its spawn snapshot. The old 120s expiry
        // dropped this inventory gain even though the quest and world were unchanged.
        Object world = new Object();
        spawnReward(10, world, System.currentTimeMillis() - 180_000);
        Object reward = call("takeReward", new Class<?>[]{});
        equal(observed(reward, "Voidgloom", "Null Ovoid", world, 14), 4);
        equal(BossDrops.rewardQuantity(1, observed(reward, "Voidgloom", "Null Ovoid", world, 14)), 4);
        equal(call("takeReward", new Class<?>[]{}), null);
        equal(observed(reward, "Revenant", "Null Ovoid", world, 14), 0);
        equal(observed(reward, "Voidgloom", "Null Atom", world, 14), 0);
        equal(observed(reward, "Voidgloom", "Null Ovoid", new Object(), 14), 0);
        spawnReward(10, world, 1L);
        call("rewardQuestLine", new Class<?>[]{String.class}, "SLAYER QUEST FAILED!");
        equal(call("takeReward", new Class<?>[]{}), null);
        spawnReward(10, world, 1L);
        call("rewardQuestLine", new Class<?>[]{String.class}, "SLAYER QUEST STARTED!");
        equal(call("takeReward", new Class<?>[]{}), null);
        spawnReward(10, world, 1L);
        spawnReward(12, world, 2L);
        reward = call("takeReward", new Class<?>[]{});
        equal(observed(reward, "Voidgloom", "Null Ovoid", world, 14), 2);
        // Starting the next quest must not wipe a slain boss's pending reward.
        setStatic("death", null);
        Object pending = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Voidgloom", 2L, false);
        set(pending, "reward", reward);
        call("rewardQuestLine", new Class<?>[]{String.class}, "SLAYER QUEST STARTED!");
        equal(value(pending, "reward"), reward);
        call("rewardWorld", new Class<?>[]{Object.class}, new Object());
        equal(value(pending, "reward"), null);
        setStatic("death", null);
        spawnReward(10, world, 1L);
        call("rewardWorld", new Class<?>[]{Object.class}, (Object) null);
        equal(call("takeReward", new Class<?>[]{}), null);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Revenant", "Revenant Viscera", 1_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Revenant", "Revenant Viscera", 2_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Dragon", "Dragon Horn", 1_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Dragon", "Dragon Horn", 2_000L), true);
        // The server's obtained line announces an epic pet in purple, even though the
        // default drop list puts the Ender Dragon pet in the legendary tier.
        String epicLine = "[DRAGON] Player §ehas obtained §5§7[Lvl 1] §5Ender Dragon§e!";
        Drops.Drop epic = (Drops.Drop) call("obtainedDrop", new Class<?>[]{String.class, String.class},
                epicLine, "[Lvl 1] Ender Dragon");
        equal(epic.tier(), 2);
        String epicKey = (String) call("petKey", new Class<?>[]{String.class, int.class}, epic.item(), epic.tier());
        String legendaryKey = (String) call("petKey", new Class<?>[]{String.class, int.class},
                epic.item(), 3);
        equal(epicKey, "[Lvl 1] Ender Dragon (Epic)");
        equal(legendaryKey, "[Lvl 1] Ender Dragon (Legendary)");
        equal(call("tier", new Class<?>[]{String.class}, epicKey), 2);
        equal(call("tier", new Class<?>[]{String.class}, legendaryKey), 3);
        equal(call("petKey", new Class<?>[]{String.class, int.class}, "Dragon Claw", 2), "Dragon Claw");
        equal(call("hidden", new Class<?>[]{String.class}, "Wise Dragon Chestplate"), true);
        equal(call("hidden", new Class<?>[]{String.class}, "Protector Dragon Boots"), true);
        equal(call("hidden", new Class<?>[]{String.class}, "Travel Scroll to Dragon Nest"), true);
        // The personal debug line and the later obtained broadcast are one pet.
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Old Dragon", epicKey, 100_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Old Dragon", epicKey, 107_000L), true);
        read(List.of("drop\tOld Dragon\t" + epicKey + "\t1",
                "drop\tOld Dragon\t" + legendaryKey + "\t2",
                "tier\t[Lvl 1] Ender Dragon\t3"));
        equal(((Map<?, ?>) value(map("loaded").get("Old Dragon"), "drops")).get(epicKey), 1);
        equal(((Map<?, ?>) value(map("loaded").get("Old Dragon"), "drops")).get(legendaryKey), 2);
        equal(call("tier", new Class<?>[]{String.class}, epicKey), 2);
        equal(saved().contains("drop\tOld Dragon\t" + epicKey + "\t1"), true);

        // The dragon loot summary, lines from the 2026-10-07 log after DragSim dropped the
        // item from its dragon debug line.
        String[] aotd = (String[]) call("summaryLine", new Class<?>[]{String.class}, "- 1x Aspect of the Dragons");
        equal(aotd[0], "Aspect of the Dragons");
        equal(aotd[1], "1");
        equal(((String[]) call("summaryLine", new Class<?>[]{String.class}, "- 1x [Lvl 1] Tiger"))[0], "[Lvl 1] Tiger");
        equal(call("summaryLine", new Class<?>[]{String.class}, "-560,000 coins"), null);
        equal(call("summaryBoss", new Class<?>[]{List.class, String.class},
                List.of("Aspect of the Dragons", "4x Old Dragon Fragment".substring(3)), "Wise Dragon"), "Old Dragon");
        equal(call("summaryBoss", new Class<?>[]{List.class, String.class},
                List.of("Aspect of the Dragons", "Crystal Fragment"), "Wise Dragon"), "Wise Dragon");
        familyProfitChecks();
        pendingDropChecks();
        BossDropsBoundaryChecks.main(args);
        familyHudChecks();
        System.out.println(checks + " Boss Drops preservation checks passed");
    }

    private static void familyHudChecks() throws Exception {
        // One card now, not one per family: you can only run one Slayer quest at a
        // time, so two menu entries with two Periods and two positions bought nothing.
        var profit = BossDrops.slayerProfitModule();
        equal(profit.id(), "slayer.profit");
        equal(profit.title(), "Slayer Profit");
        Setting.Choice period = (Setting.Choice) profit.settings().stream()
                .filter(s -> s.label().equals("Period")).findFirst().orElseThrow();
        equal(period.get().get(), "Session");

        // The SETTINGS merged; the COUNTS did not. Each family still keeps its own
        // history, and the card reads whichever quest is running - a Riftborn quest
        // must never show Voidgloom's numbers just because they share a card now.
        read(List.of(SCHEMA, "kill	Voidgloom	10", "kill	Riftborn	100"));
        map("session").clear();
        Object voidSession = call("of", new Class<?>[]{Map.class, String.class}, map("session"), "Voidgloom");
        Object riftSession = call("of", new Class<?>[]{Map.class, String.class}, map("session"), "Riftborn");
        set(voidSession, "kills", 3);
        set(riftSession, "kills", 5);
        Object voidNew = call("of", new Class<?>[]{Map.class, String.class}, map("unsaved"), "Voidgloom");
        Object riftNew = call("of", new Class<?>[]{Map.class, String.class}, map("unsaved"), "Riftborn");
        set(voidNew, "kills", 1);
        set(riftNew, "kills", 2);
        equal(value(call("hudCount", new Class<?>[]{String.class, String.class}, "Voidgloom", "Session"), "kills"), 3);
        equal(value(call("hudCount", new Class<?>[]{String.class, String.class}, "Riftborn", "Session"), "kills"), 5);

        // Period now switches both, because it is one card.
        period.next(1);
        equal(period.get().get(), "All Time");
        equal(value(call("hudCount", new Class<?>[]{String.class, String.class}, "Voidgloom", period.get().get()), "kills"), 11);
        equal(value(call("hudCount", new Class<?>[]{String.class, String.class}, "Riftborn", period.get().get()), "kills"), 102);

        // The family guard is unchanged: still no borrowing another family's history.
        equal(call("profitHudMatches", new Class<?>[]{String.class, String.class, boolean.class}, "Voidgloom", "Voidgloom", true), true);
        equal(call("profitHudMatches", new Class<?>[]{String.class, String.class, boolean.class}, "Voidgloom", "Riftborn", true), false);
        equal(call("profitHudMatches", new Class<?>[]{String.class, String.class, boolean.class}, "Riftborn", "Riftborn", false), false);
        equal(call("profitHudMatches", new Class<?>[]{String.class, String.class, boolean.class}, "Riftborn", "Enderman (unidentified)", true), false);
        // and a null family - no known quest - draws nothing rather than guessing.
        equal(call("profitHudMatches", new Class<?>[]{String.class, String.class, boolean.class}, null, "Voidgloom", true), false);

        // Turning off the generic boss readout cannot silently stop the dedicated
        // tracker from recording its quests and drops.
        setStatic("enabled", false);
        equal(call("trackingEnabled", new Class<?>[]{}), true);
        profit.setEnabled().accept(false);
        equal(call("trackingEnabled", new Class<?>[]{}), false);
        setStatic("enabled", true);
        profit.setEnabled().accept(true);
        period.set().accept("Session");
    }

    @SuppressWarnings("unchecked")
    private static void pendingDropChecks() throws Exception {
        clearHeld();
        setStatic("death", null);
        setStatic("rewardWindow", null);
        setStatic("profitQuestActive", false);
        setStatic("slayer", "Riftborn");
        Object world = new Object();
        setStatic("messageWorld", world);
        // Generic BONUS wording also comes from altar sacrifices. Without a live
        // personal Slayer reward window it proves neither a boss kill nor its loot.
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 1_000L, world), false);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Wise Dragon Fragment", 1_000L, world), false);
        call("rewardSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Ovoid", 10, world, 1_000L);
        setStatic("profitQuestActive", true);
        setStatic("profitLastLine", 1_000L);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 1_100L, world), true);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Golden Eye", 1_100L, world), false);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Summoning Eye", 1_100L, world), false);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 1_100L, new Object()), false);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 1_000L + 15 * 60_000L, world), false);
        // These quantities test generic independent reward lines, not an assumed
        // streak-five payout. An extra item before SLAIN cannot replace Riftstring.
        call("hold", new Class<?>[]{Drops.Drop.class, long.class}, new Drops.Drop("Riftstring", 1), 1_000L);
        call("hold", new Class<?>[]{Drops.Drop.class, long.class, boolean.class}, new Drops.Drop("Null Atom", 2, 2), 1_010L, true);
        call("hold", new Class<?>[]{Drops.Drop.class, long.class, boolean.class}, new Drops.Drop("Null Ovoid", 0, 3), 1_020L, true);
        call("hold", new Class<?>[]{Drops.Drop.class, long.class, boolean.class}, new Drops.Drop("Null Ovoid", 0, 2), 1_030L, true);
        Object unrelated = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Golem", 1_500L, true);
        equal(((List<?>) value(unrelated, "drops")).size(), 0);
        Object own = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Riftborn", 1_600L, true);
        List<Drops.Drop> pending = (List<Drops.Drop>) value(own, "drops");
        equal(pending.size(), 4);
        equal(pending.get(0).item(), "Riftstring");
        equal(pending.get(1).item(), "Null Atom");
        equal(pending.get(1).quantity(), 2);
        equal(call("announcedQuantity", new Class<?>[]{List.class, String.class}, pending, "Null Ovoid"), 5);
        equal(BossDrops.rewardQuantity(5, 7), 7);
        set(own, "dead", true);
        setStatic("profitQuestActive", false);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 1_700L, world), true);
        equal(call("slayerBonusEligible", new Class<?>[]{String.class, long.class, Object.class}, "Null Atom", 4_101L, world), false);
        // Returning the same death cannot consume the pending list a second time.
        call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Riftborn", 1_700L, true);
        equal(pending.size(), 4);
        map("session").clear();
        map("unsaved").clear();
        for (Drops.Drop drop : pending) {
            if (!drop.item().equals("Null Ovoid")) call("recordDrop", new Class<?>[]{String.class, String.class, int.class},
                    "Riftborn", drop.item(), drop.quantity());
        }
        call("recordDrop", new Class<?>[]{String.class, String.class, int.class}, "Riftborn", "Null Ovoid", BossDrops.rewardQuantity(5, 7));
        Map<?, ?> tracked = (Map<?, ?>) value(map("session").get("Riftborn"), "drops");
        equal(tracked.get("Riftstring"), 1);
        equal(tracked.get("Null Atom"), 2);
        equal(tracked.get("Null Ovoid"), 7);

        setStatic("death", null);
        clearHeld();
        call("hold", new Class<?>[]{Drops.Drop.class, long.class}, new Drops.Drop("Riftstring", 1), 2_000L);
        own = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Riftborn", 4_001L, true);
        equal(((List<?>) value(own, "drops")).size(), 0);
        setStatic("death", null);
        clearHeld();
        for (int i = 0; i < 65; i++) call("hold", new Class<?>[]{Drops.Drop.class, long.class}, new Drops.Drop("Riftstring", 1), 5_000L + i);
        own = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Riftborn", 5_100L, true);
        equal(((List<?>) value(own, "drops")).size(), 64);
        setStatic("death", null);
        clearHeld();
        call("hold", new Class<?>[]{Drops.Drop.class, long.class}, new Drops.Drop("Riftstring", 1), 6_000L);
        Object otherWorld = new Object();
        call("rewardWorld", new Class<?>[]{Object.class}, otherWorld);
        setStatic("messageWorld", otherWorld);
        own = call("open", new Class<?>[]{String.class, long.class, boolean.class}, "Riftborn", 6_100L, true);
        equal(((List<?>) value(own, "drops")).size(), 0);
        setStatic("death", null);
        setStatic("rewardWindow", null);
        clearHeld();
    }

    private static void clearHeld() throws Exception {
        Field field = BossDrops.class.getDeclaredField("held");
        field.setAccessible(true);
        ((List<?>) field.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private static void familyProfitChecks() throws Exception {
        // The menu fixtures in SlayerCostsChecks name Riftborn at 13,750 XP. Endermen
        // alone and shared items such as Ovoids do not identify the boss family.
        equal(call("questBoss", new Class<?>[]{String.class}, "Slay 11,000 Combat XP worth of Endermen"), "Voidgloom");
        equal(call("questBoss", new Class<?>[]{String.class}, "Slay 13,750 Combat XP worth of Endermen"), "Riftborn");
        equal(call("questBoss", new Class<?>[]{String.class}, "Slay 99,999 Combat XP worth of Endermen"), "Enderman (unidentified)");
        equal(call("questBoss", new Class<?>[]{String.class}, "Slay 13,750 Combat XP worth of Zombies"), "Revenant");
        equal(call("questBoss", new Class<?>[]{String.class}, "Slay 4,000 Combat XP worth of Wolves"), null);
        equal(call("namedQuestBoss", new Class<?>[]{String.class}, "Slayer: Riftborn Seraph"), "Riftborn");
        equal(call("namedQuestBoss", new Class<?>[]{String.class}, "Slayer: Voidgloom Seraph IV"), "Voidgloom");
        equal(call("namedQuestBoss", new Class<?>[]{String.class}, "WOW! Player found a Core from the Riftborn Seraph!"), null);
        equal(call("resolvedStartBoss", new Class<?>[]{String.class, String.class}, "Voidgloom",
                "Slay 13,750 Combat XP worth of Endermen"), "Riftborn");
        equal(call("rewardItem", new Class<?>[]{String.class}, "Riftborn"), "Null Ovoid");
        equal(BossDrops.isSlayerDropName("Riftstring"), true);
        equal(call("counted", new Class<?>[]{String.class, String.class}, "Riftborn", "Riftstring"), true);
        // New schema preserves each family, and a second save/load cannot migrate it again.
        read(List.of(SCHEMA, "kill\tVoidgloom\t3", "kill\tRiftborn\t5", "kill\t" + LEGACY + "\t12",
                "drop\tVoidgloom\tNull Ovoid\t9", "drop\tRiftborn\tNull Ovoid\t20",
                "cost\tVoidgloom\t3\t15000000\t0\t0", "cost\tRiftborn\t5\t75000000\t0\t0",
                "time\tVoidgloom\t10000", "time\tRiftborn\t30000"));
        equal(value(map("loaded").get("Voidgloom"), "kills"), 3);
        equal(value(map("loaded").get("Riftborn"), "kills"), 5);
        equal(value(map("loaded").get(LEGACY), "kills"), 12);
        equal(((Map<?, ?>) value(map("loaded").get("Voidgloom"), "drops")).get("Null Ovoid"), 9);
        equal(((Map<?, ?>) value(map("loaded").get("Riftborn"), "drops")).get("Null Ovoid"), 20);
        equal(value(map("loaded").get("Voidgloom"), "spawnCost"), 15_000_000L);
        equal(value(map("loaded").get("Riftborn"), "spawnCost"), 75_000_000L);
        read(saved());
        equal(value(map("loaded").get("Riftborn"), "activeMs"), 30_000L);
        equal(value(map("loaded").get("Riftborn"), "incompleteTime"), false);
        equal(value(map("loaded").get(LEGACY), "incompleteTime"), true);
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 0L);
        // Quest time excludes idle time, failed quest downtime, and a changed world.
        map("session").clear();
        map("unsaved").clear();
        Object world = new Object();
        setStatic("profitWorld", world);
        setStatic("profitTickAt", 1_000L);
        setStatic("profitLastLine", 1_000L);
        setStatic("profitQuestActive", false);
        call("profitQuestLine", new Class<?>[]{String.class, long.class},
                "SLAYER QUEST STARTED!\nSlay 11,000 Combat XP worth of Endermen", 1_000L);
        call("clockProfit", new Class<?>[]{long.class, Object.class}, 2_000L, world);
        equal(value(map("session").get("Voidgloom"), "activeMs"), 1_000L);
        call("profitQuestLine", new Class<?>[]{String.class, long.class}, "SLAYER BOSS SLAIN!", 3_000L);
        call("clockProfit", new Class<?>[]{long.class, Object.class}, 10_000L, world);
        equal(value(map("session").get("Voidgloom"), "activeMs"), 2_000L);
        call("profitQuestLine", new Class<?>[]{String.class, long.class},
                "SLAYER QUEST STARTED!\nSlay 13,750 Combat XP worth of Endermen", 10_000L);
        call("clockProfit", new Class<?>[]{long.class, Object.class}, 12_000L, world);
        equal(value(map("session").get("Riftborn"), "activeMs"), 2_000L);
        call("profitQuestLine", new Class<?>[]{String.class, long.class}, "SLAYER QUEST FAILED!", 13_000L);
        call("clockProfit", new Class<?>[]{long.class, Object.class}, 20_000L, world);
        equal(value(map("session").get("Riftborn"), "activeMs"), 3_000L);
        call("profitQuestLine", new Class<?>[]{String.class, long.class},
                "SLAYER QUEST STARTED!\nSlay 13,750 Combat XP worth of Endermen", 20_000L);
        call("clockProfit", new Class<?>[]{long.class, Object.class}, 22_000L, new Object());
        equal(value(map("session").get("Riftborn"), "activeMs"), 3_000L);
        // Item selection uses quantity times unit price, and profit includes items
        // outside the top five. Unknown-valued loot cannot silently become zero.
        Field pricesField = NpcPrices.class.getDeclaredField("custom");
        pricesField.setAccessible(true);
        Map<String, Long> prices = (Map<String, Long>) pricesField.get(null);
        prices.clear();
        prices.put("null ovoid", 1_000_000L);
        prices.put("null atom", 2_000_000L);
        prices.put("judgement core", 10_000_000L);
        prices.put("seraph quill", 3_000_000L);
        prices.put("rift pass", 4_000_000L);
        prices.put("whetstone", 5_000_000L);
        Object count = call("of", new Class<?>[]{Map.class, String.class}, map("session"), "Riftborn");
        Map<String, Integer> drops = (Map<String, Integer>) value(count, "drops");
        drops.put("Null Ovoid", 20);
        drops.put("Null Atom", 1);
        drops.put("Judgement Core", 1);
        drops.put("Seraph Quill", 1);
        drops.put("Rift Pass", 1);
        drops.put("Whetstone", 1);
        List<Map.Entry<String, Integer>> list = (List<Map.Entry<String, Integer>>) call("valueSorted", new Class<?>[]{Map.class}, drops);
        equal(list.subList(0, 5).stream().map(Map.Entry::getKey).toList(),
                List.of("Null Ovoid", "Judgement Core", "Whetstone", "Rift Pass", "Seraph Quill"));
        set(count, "spawnCost", 15_000_000L);
        equal(call("profit", new Class<?>[]{count.getClass()}, count), 29_000_000L);
        equal(call("missingValues", new Class<?>[]{count.getClass()}, count), false);
        drops.put("Seraph's Halo", 1);
        equal(call("missingValues", new Class<?>[]{count.getClass()}, count), true);
        equal(call("profit", new Class<?>[]{count.getClass()}, count), 29_000_000L);
        equal(call("compactCoins", new Class<?>[]{long.class}, -13_600_000_000L), "-13.6b");
        equal(call("periodHit", new Class<?>[]{int[].class, float.class, double.class, double.class, int.class},
                new int[]{20, 30, 220, 100}, 1f, 210d, 35d, 44), true);
        equal(call("periodHit", new Class<?>[]{int[].class, float.class, double.class, double.class, int.class},
                new int[]{20, 30, 220, 100}, 1f, 210d, 55d, 44), false);
        equal(call("periodHit", new Class<?>[]{int[].class, float.class, double.class, double.class, int.class},
                new int[]{20, 30, 440, 200}, 2f, 410d, 42d, 44), true);
        prices.clear();
    }

    private static void read(List<String> lines) throws Exception {
        map("loaded").clear();
        map("unsaved").clear();
        map("seen").clear();
        call("readCounts", new Class<?>[]{List.class}, lines);
    }

    @SuppressWarnings("unchecked")
    private static List<String> saved() throws Exception {
        return (List<String>) call("savedLines", new Class<?>[]{});
    }

    private static Map<?, ?> map(String name) throws Exception {
        Field field = BossDrops.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static Object value(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = BossDrops.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BossDrops.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static void spawnReward(int baseline, Object world, long at) throws Exception {
        call("rewardSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Voidgloom", "Null Ovoid", baseline, world, at);
    }

    private static int observed(Object reward, String boss, String item, Object world, int current) throws Exception {
        return (int) call("observedReward", new Class<?>[]{reward.getClass(), String.class, String.class, Object.class, int.class},
                reward, boss, item, world, current);
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
