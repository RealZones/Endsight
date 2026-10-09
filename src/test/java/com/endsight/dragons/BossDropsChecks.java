package com.endsight.dragons;

import com.endsight.qol.Drops;
import com.endsight.slayers.SlayerCostsChecks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class BossDropsChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SlayerCostsChecks.main(args);
        Field layout = BossDrops.class.getDeclaredField("compactLayout");
        layout.setAccessible(true);
        equal(layout.getBoolean(null), true);
        read(List.of("kill\tRevenant\t125", "kill\tVoidgloom\t12", "kill\tGolden Dragon\t8",
                "drop\tRevenant\tScythe Blade\t3", "drop\tVoidgloom\tJudgement Core\t2",
                "tier\tJudgement Core\t3"));
        Map<?, ?> loaded = map("loaded");
        equal(value(loaded.get("Revenant"), "kills"), 125);
        equal(value(loaded.get("Revenant"), "historicalCost"), 625_000_000L);
        equal(value(loaded.get("Voidgloom"), "historicalCost"), 180_000_000L);
        equal(value(loaded.get("Golden Dragon"), "kills"), 8);
        equal(value(loaded.get("Golden Dragon"), "historicalCost"), 0L);
        equal(((Map<?, ?>) value(loaded.get("Voidgloom"), "drops")).get("Judgement Core"), 2);
        equal(((Map<?, ?>) value(loaded.get("Revenant"), "drops")).get("Scythe Blade"), 3);
        equal(call("costText", new Class<?>[]{loaded.get("Voidgloom").getClass()}, loaded.get("Voidgloom")), "~-180m");

        List<String> saved = saved();
        equal(saved.contains("kill\tRevenant\t125"), true);
        equal(saved.contains("drop\tVoidgloom\tJudgement Core\t2"), true);
        equal(saved.contains("cost\tVoidgloom\t0\t0\t0\t180000000"), true);
        read(saved);
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 180_000_000L);
        equal(saved(), saved);
        Object increment = call("of", new Class<?>[]{Map.class, String.class}, map("unsaved"), "Voidgloom");
        set(increment, "starts", 2);
        set(increment, "spawnCost", 10_000_000L);
        set(increment, "kills", 1);
        saved = saved();
        equal(saved.contains("kill\tVoidgloom\t13"), true);
        equal(saved.contains("cost\tVoidgloom\t2\t10000000\t0\t180000000"), true);
        read(saved);
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 180_000_000L);
        equal(value(map("loaded").get("Voidgloom"), "spawnCost"), 10_000_000L);

        read(List.of("cost\tVoidgloom\t3\t30000000\t1\t180000000", "kill\tVoidgloom\t14",
                "drop\tVoidgloom\tJudgement Core\t2"));
        Object total = map("loaded").get("Voidgloom");
        equal(value(total, "kills"), 14);
        equal(value(total, "historicalCost"), 180_000_000L);
        equal(value(total, "spawnCost"), 30_000_000L);
        equal(call("costText", new Class<?>[]{total.getClass()}, total), "~-210m + ?");
        read(saved());
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 180_000_000L);

        read(List.of("kill\tVoidgloom\t100", "cost\tVoidgloom\t0\t0\t0\t0"));
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 0L);
        read(List.of("kill\tEnderman\t5", "kill\tZombie\t2"));
        equal(value(map("loaded").get("Voidgloom"), "kills"), 5);
        equal(value(map("loaded").get("Voidgloom"), "historicalCost"), 75_000_000L);
        equal(value(map("loaded").get("Revenant"), "historicalCost"), 10_000_000L);
        equal(call("dropPrefix", new Class<?>[]{int.class}, 1_000), "1,000x ");
        equal(call("compactCoins", new Class<?>[]{long.class}, 15_000_000_000L), "15b");
        equal(BossDrops.rewardQuantity(1, 4), 4);
        equal(BossDrops.rewardQuantity(1, 0), 1);
        equal(BossDrops.rewardQuantity(0, -3), 0);
        equal(call("rewardItem", new Class<?>[]{String.class}, "Revenant"), "Revenant Viscera");
        equal(call("rewardItem", new Class<?>[]{String.class}, "Voidgloom"), "Null Ovoid");
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Revenant", "Revenant Viscera", 1_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Revenant", "Revenant Viscera", 2_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Dragon", "Dragon Horn", 1_000L), false);
        equal(call("repeatedDrop", new Class<?>[]{String.class, String.class, long.class},
                "Dragon", "Dragon Horn", 2_000L), true);
        setStatic("dragonActivityAt", 1_000L);
        setStatic("ownDragonUp", false);
        equal(call("dragonHudVisible", new Class<?>[]{long.class, boolean.class}, 60_999L, false), true);
        equal(call("dragonHudVisible", new Class<?>[]{long.class, boolean.class}, 61_000L, true), false);
        setStatic("ownDragonUp", true);
        equal(call("dragonHudVisible", new Class<?>[]{long.class, boolean.class}, 61_000L, true), true);
        equal(call("dragonHudVisible", new Class<?>[]{long.class, boolean.class}, 61_000L, false), false);
        setStatic("dragonActivityAt", 0L);
        setStatic("ownDragonUp", false);
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
        System.out.println(checks + " Boss Drops preservation checks passed");
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

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
