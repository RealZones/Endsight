package com.endsight.hud;

import com.endsight.dragons.BossDrops;
import com.endsight.qol.Drops;
import com.endsight.qol.NpcPrices;
import com.endsight.qol.Rarity;
import com.endsight.storage.MiningSession;
import com.endsight.storage.PowderTracker;
import com.endsight.storage.VoidFragments;
import com.endsight.ui.Palette;
import com.endsight.ui.Theme;
import com.endsight.zealots.ZealotTracker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cached HUD text must follow live state without caching a Font or a measured width. */
public final class TrackerRowsChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // Headless runners have no Fabric config directory. This fixture exercises
        // the tracker, with an explicit empty rarity-list source instead of disk I/O.
        put(Drops.class, "rules", List.of());
        dragonHistory();
        bossRows();
        zealotRows();
        miningRows();
        powderRows();
        System.out.println(checks + " tracker row checks passed");
    }

    private static void dragonHistory() throws Exception {
        clearBosses();
        Object oldDragon = count("loaded", "Golden Dragon");
        set(oldDragon, "kills", 8);
        set(count("unsaved", "Golden Dragon"), "kills", 1);
        set(count("session", "Golden Dragon"), "kills", 2);
        Object unrelated = count("loaded", "Revenant");
        // Reading kills or another boss's HUD must never walk unrelated loot. A
        // throwing map catches the old full-history merge without a timing test.
        set(unrelated, "drops", new AbstractMap<String, Integer>() {
            @Override public Set<Map.Entry<String, Integer>> entrySet() {
                throw new AssertionError("count-only HUD read unrelated drop history");
            }
        });
        equal(call(BossDrops.class, "dragonKills", new Class<?>[]{String.class, boolean.class}, null, true), 9);
        equal(call(BossDrops.class, "dragonKills", new Class<?>[]{String.class, boolean.class}, "Golden", true), 9);
        equal(call(BossDrops.class, "dragonKills", new Class<?>[]{String.class, boolean.class}, "Golden", false), 2);
        int before = map(BossDrops.class, "session").size();
        equal(call(BossDrops.class, "dragonKills", new Class<?>[]{String.class, boolean.class}, "Arcane", false), 0);
        equal(map(BossDrops.class, "session").size(), before);
        Object warden = call(BossDrops.class, "hudCount", new Class<?>[]{String.class, String.class}, "Warden", "All Time");
        equal(field(warden.getClass(), "kills").get(warden), 0);
        map(BossDrops.class, "loaded").remove("Revenant");
    }

    private static void bossRows() throws Exception {
        clearBosses();
        Object live = count("session", "Warden");
        set(live, "kills", 1);
        Object rows = bossRows("Warden", "Session", false, 1_000);
        same(rows, bossRows("Warden", "Session", false, 1_001), "unchanged boss rows rebuilt");
        set(live, "kills", 2);
        equal(row(bossRows("Warden", "Session", false, 1_001), "Kills").value(), "2");
        set(count("loaded", "Warden"), "kills", 9);
        equal(row(bossRows("Warden", "All Time", false, 1_001), "Kills").value(), "9");
        equal(row(bossRows("Warden", "Session", true, 1_001), "Kills").value(), "12");
        equal(row(bossRows("Warden", "Session", false, 1_001), "Kills").value(), "2");
        rows = bossRows("Warden", "Session", false, 1_001);
        yes(rows != bossRows("Warden", "Session", false, 1_250), "rarity/time refresh did not expire");

        Object slayer = count("session", "Voidgloom");
        drops(slayer).put("Null Ovoid", 2);
        map(Rarity.class, "cache").put("Null Ovoid", 0xAA00AA);
        Map<String, Object> prices = map(NpcPrices.class, "custom");
        prices.put("null ovoid", 1_000_000L);
        boolean learning = NpcPrices.learning();
        Object first = bossRows("Voidgloom", "Session", false, 2_000);
        equal(row(first, "Profit").value(), "2m");
        Object warden = bossRows("Warden", "All Time", false, 2_000);
        same(first, bossRows("Voidgloom", "Session", false, 2_001), "another visible HUD evicted Slayer rows");
        same(warden, bossRows("Warden", "All Time", false, 2_001), "Slayer HUD evicted Boss Drops rows");
        prices.put("null ovoid", 2_000_000L);
        NpcPrices.learning(!learning); // Public price-provider invalidation, with no file writes.
        Object changed = bossRows("Voidgloom", "Session", false, 2_000);
        equal(row(changed, "Profit").value(), "4m");
        drops(slayer).put("Null Ovoid", 3);
        equal(row(bossRows("Voidgloom", "Session", false, 2_000), "Profit").value(), "6m");
        Palette previous = Theme.palette();
        Theme.set(Palette.ALL.get(1));
        equal(row(bossRows("Voidgloom", "Session", false, 2_000), "Profit").colour(), Theme.pos());
        Theme.set(previous);
        prices.clear();
        NpcPrices.learning(learning);
    }

    private static void zealotRows() throws Exception {
        call(ZealotTracker.class, "resetSession", new Class<?>[]{});
        Object empty = call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false);
        same(empty, call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false), "unchanged zealot rows rebuilt");
        put(ZealotTracker.class, "golden", 1);
        Object one = call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false);
        equal(row(one, "Profit").value(), "50.0m");
        Map<String, Object> prices = map(NpcPrices.class, "custom");
        boolean learning = NpcPrices.learning();
        prices.put("golden eye", 65_000_000L);
        NpcPrices.learning(!learning);
        equal(row(call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false), "Profit").value(), "65.0m");
        Object tooltip = call(ZealotTracker.class, "profitTooltip", new Class<?>[]{});
        same(tooltip, call(ZealotTracker.class, "profitTooltip", new Class<?>[]{}), "unchanged tooltip rebuilt");
        put(ZealotTracker.class, "golden", 2);
        yes(tooltip != call(ZealotTracker.class, "profitTooltip", new Class<?>[]{}), "tooltip kept old counts");
        Object sample = call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, true);
        yes(sample != call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false), "sample rows escaped into live HUD");
        call(ZealotTracker.class, "resetSession", new Class<?>[]{});
        equal(row(call(ZealotTracker.class, "hudRows", new Class<?>[]{boolean.class}, false), "Profit").value(), "0");
        prices.clear();
        NpcPrices.learning(learning);
    }

    private static void miningRows() throws Exception {
        call(MiningSession.class, "reset", new Class<?>[]{});
        put(MiningSession.class, "hudPeriod", "Session");
        put(MiningSession.class, "materialUnits", "Raw");
        put(MiningSession.class, "activeMs", 60_000L);
        map(MiningSession.class, "totals").put("Amethyst", 6_400L);
        Object first = call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false);
        same(first, call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false), "unchanged mining rows rebuilt");
        map(MiningSession.class, "totals").put("Amethyst", 12_800L);
        Object changed = call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false);
        yes(first != changed, "material change did not invalidate mining rows");
        put(MiningSession.class, "materialUnits", "Ench");
        Object enchanted = call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false);
        yes(row(enchanted, "§d◇ Amethyst").value().contains("Flawed"), "unit change kept raw count");
        Object lifetime = field(MiningSession.class, "lifetime").get(null);
        set(lifetime, "activeMs", 90_000L);
        put(MiningSession.class, "hudPeriod", "Total");
        Object total = call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false);
        equal(row(total, "Time mined").value(), "1m 30s");
        put(MiningSession.class, "hudPeriod", "Session");
        equal(row(call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false), "Time mined").value(), "1m 0s");
        put(MiningSession.class, "breakdown", false);
        Object compact = call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false);
        equal(((List<?>) compact).size(), 3);
        put(VoidFragments.class, "found", 2L);
        yes(!row(compact, "§6Profit").value().equals(row(call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false), "§6Profit").value()),
                "fragment change kept old profit");
        call(MiningSession.class, "reset", new Class<?>[]{});
        equal(row(call(MiningSession.class, "hudRows", new Class<?>[]{boolean.class}, false), "Time mined").value(), "0s");
    }

    private static void powderRows() throws Exception {
        long[] totals = (long[]) field(PowderTracker.class, "total").get(null);
        long[] baseline = (long[]) field(PowderTracker.class, "sessionBase").get(null);
        boolean[] known = (boolean[]) field(PowderTracker.class, "sessionSet").get(null);
        totals[0] = 100; totals[1] = 200;
        baseline[0] = 100; baseline[1] = 200;
        known[0] = true; known[1] = false;
        put(PowderTracker.class, "activeMs", 60_000L);
        Object first = call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, true);
        same(first, call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, true), "unchanged powder rows rebuilt");
        equal(row(first, "§bEnder session").value(), "+0");
        equal(row(first, "§dVoid session").value(), "—");
        totals[0] = 200;
        Object changed = call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, true);
        equal(row(changed, "§bEnder session").value(), "+100");
        known[1] = true;
        equal(row(call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, true), "§dVoid session").value(), "+0");
        put(PowderTracker.class, "activeMs", 0L);
        equal(row(call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, true), "Ender/h").value(), "§b—");
        Object sample = call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, true, true);
        yes(sample != call(PowderTracker.class, "hudRows", new Class<?>[]{boolean.class, boolean.class}, false, false), "powder sample/paused cache was shared");
        Object fuel = call(PowderTracker.class, "fuelText", new Class<?>[]{long.class, long.class}, 2_776L, 3_000L);
        equal(fuel, "2,776/3K (93%)");
        same(fuel, call(PowderTracker.class, "fuelText", new Class<?>[]{long.class, long.class}, 2_776L, 3_000L), "unchanged fuel text rebuilt");
        equal(call(PowderTracker.class, "fuelText", new Class<?>[]{long.class, long.class}, 2_000L, 3_000L), "2,000/3K (67%)");
    }

    private static Object bossRows(String boss, String period, boolean sample, long now) throws Exception {
        return call(BossDrops.class, "countsHudRows", new Class<?>[]{String.class, String.class, boolean.class, boolean.class, long.class},
                boss, period, true, sample, now);
    }

    private static void clearBosses() throws Exception {
        for (String name : List.of("session", "loaded", "unsaved")) map(BossDrops.class, name).clear();
    }

    private static Object count(String map, String boss) throws Exception {
        return call(BossDrops.class, "of", new Class<?>[]{Map.class, String.class}, map(BossDrops.class, map), boss);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> drops(Object count) throws Exception {
        return (Map<String, Integer>) field(count.getClass(), "drops").get(count);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Class<?> owner, String name) throws Exception {
        return (Map<String, Object>) field(owner, name).get(null);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static void put(Class<?> owner, String name, Object value) throws Exception {
        field(owner, name).set(null, value);
    }

    private static Object call(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }

    private static HudCard.Row row(Object rows, String label) {
        return ((List<?>) rows).stream().map(HudCard.Row.class::cast).filter(row -> row.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("missing row " + label + ": " + rows));
    }

    private static String stringRow(Object rows, String label) {
        return ((List<?>) rows).stream().map(String[].class::cast).filter(row -> row[0].equals(label)).findFirst().orElseThrow()[1];
    }

    private static void same(Object first, Object second, String reason) { yes(first == second, reason); }

    private static void equal(Object actual, Object expected) {
        yes(java.util.Objects.equals(actual, expected), "expected " + expected + ", got " + actual);
    }

    private static void yes(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
}
