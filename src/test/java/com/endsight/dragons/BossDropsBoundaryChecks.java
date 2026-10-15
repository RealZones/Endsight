package com.endsight.dragons;

import com.endsight.qol.Drops;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/** Consecutive personal kills must retain separate inventory reward snapshots. */
public final class BossDropsBoundaryChecks {
    private static int checks;
    private static final Class<?> DEATH = nested("Death");
    private static final Class<?> REWARD = nested("RewardWindow");

    public static void main(String[] args) throws Exception {
        Object oldDeath = staticValue("death"), oldReward = staticValue("rewardWindow"), oldWorld = staticValue("messageWorld");
        Object oldSlayer = staticValue("slayer");
        Object oldAtoms = staticValue("atomWindow");
        List<?> oldHeld = List.copyOf((List<?>) staticValue("held"));
        try {
            boundaryChecks();
            t5Atoms();
        } finally {
            setStatic("death", oldDeath);
            setStatic("rewardWindow", oldReward);
            setStatic("atomWindow", oldAtoms);
            setStatic("messageWorld", oldWorld);
            setStatic("slayer", oldSlayer);
            restoreHeld(oldHeld);
        }
        System.out.println("BossDropsBoundaryChecks: " + checks + " checks passed");
    }

    private static void boundaryChecks() throws Exception {
        Object world = new Object();
        reset(world);
        Ledger ledger = new Ledger();

        // These are inventory/announcement fixtures, not assumed server payouts.
        // The next spawn happens just 500ms after SLAIN, inside the loot wait.
        spawn(world, 10, 900L);
        hold(new Drops.Drop("Riftstring", 1, 2), 990L);
        hold(new Drops.Drop("Null Ovoid", 0, 1), 991L);
        Object first = slain(1_000L);
        Object firstReward = value(first, "reward");
        equal(takePrevious(), first);
        equal(staticValue("death"), null);
        settle(first, observed(firstReward, world, 14), ledger);
        equal(ledger.kills, 1);
        equal(ledger.drops.get("Riftstring"), 2);
        equal(ledger.drops.get("Null Ovoid"), 4);

        // Closing the old death before this baseline stops new drops attaching to it.
        spawn(world, 14, 1_500L);
        hold(new Drops.Drop("Riftstring", 1), 1_590L);
        hold(new Drops.Drop("Null Atom", 0, 2), 1_591L);
        hold(new Drops.Drop("Null Ovoid", 0, 1), 1_592L);
        hold(new Drops.Drop("Null Ovoid", 0, 1), 1_593L);
        Object second = slain(1_600L);
        equal(second == first, false);
        equal(value(second, "at"), 1_600L);
        equal(((List<?>) value(first, "drops")).size(), 2);
        equal(takePrevious(), second);
        settle(second, observed(value(second, "reward"), world, 17), ledger);
        equal(ledger.kills, 2);
        equal(ledger.drops.get("Riftstring"), 3);
        equal(ledger.drops.get("Null Atom"), 2);
        equal(ledger.drops.get("Null Ovoid"), 7);
        equal(takePrevious(), null);

        // If the next spawn message was missing, its SLAIN still claims a fresh
        // death. Announcements count without inventing an inventory baseline.
        hold(new Drops.Drop("Null Ovoid", 0, 2), 1_790L);
        Object third = slain(1_800L);
        equal(value(third, "reward"), null);
        equal(takePrevious(), third);
        settle(third, 0, ledger);
        equal(ledger.kills, 3);
        equal(ledger.drops.get("Null Ovoid"), 9);

        // The boundary must not steal an unrelated lobby boss or an unfinished
        // Slayer summary. Both retain their original normal settlement path.
        Object golem = open("Golem", 2_000L);
        set(golem, "dead", true);
        equal(takePrevious(), null);
        equal(staticValue("death"), golem);
        setStatic("death", null);
        Object unfinished = open("Riftborn", 2_100L);
        equal(takePrevious(), null);
        equal(staticValue("death"), unfinished);

        // Exercise the same payout body as live settlement: an unowned summary
        // cannot turn its inventory observation into a kill or a payout.
        settle(unfinished, 20, ledger);
        equal(ledger.kills, 3);
        equal(ledger.drops.get("Null Ovoid"), 9);
        reset(null);
    }

    /**
     * The recorded T5 entity is Riftborn Seraph. Its guaranteed atoms arrive in the
     * bag, with no drop line. These are measured fixture deltas, not payout constants.
     */
    private static void t5Atoms() throws Exception {
        Object world = new Object();
        reset(world);
        setStatic("slayer", "Riftborn");
        setStatic("atomWindow", null);
        Ledger ledger = new Ledger("Riftborn");

        call("rewardSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Ovoid", 100, world, 900L);
        call("atomSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Atom", 40, world, 900L);
        Object silent = slainWithAtoms("Riftborn", 1_000L);
        equal(takePrevious(), silent);
        equal(call("takeAtomReward", new Class<?>[]{}), null);
        settle(silent, observed(value(silent, "reward"), "Riftborn", "Null Ovoid", world, 104),
                observed(value(silent, "atomReward"), "Riftborn", "Null Atom", world, 43), ledger);
        equal(ledger.kills, 1);
        equal(ledger.drops.get("Null Atom"), 3);           // no drop line, still counted
        equal(ledger.drops.get("Null Ovoid"), 4);

        // A fifth-streak bonus does not replace the main rare item or get added
        // twice when its atom is already included in the total inventory delta.
        call("rewardSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Ovoid", 104, world, 1_500L);
        call("atomSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Atom", 43, world, 1_500L);
        hold(new Drops.Drop("Riftstring", 1, 1), 1_589L);
        call("hold", new Class<?>[]{Drops.Drop.class, long.class, boolean.class}, new Drops.Drop("Null Atom", 0, 1), 1_590L, true);
        Object bonus = slainWithAtoms("Riftborn", 1_600L);
        equal(takePrevious(), bonus);
        settle(bonus, observed(value(bonus, "reward"), "Riftborn", "Null Ovoid", world, 106),
                observed(value(bonus, "atomReward"), "Riftborn", "Null Atom", world, 47), ledger);
        equal(ledger.kills, 2);
        equal(ledger.drops.get("Null Atom"), 7);
        equal(ledger.drops.get("Null Ovoid"), 6);
        equal(ledger.drops.get("Riftstring"), 1);

        // If both the main roll and the independent bonus announce an atom, the
        // two announcements count two, while the same two inventory items do not add two more.
        call("atomSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Atom", 47, world, 2_000L);
        hold(new Drops.Drop("Null Atom", 0, 1), 2_090L);
        call("hold", new Class<?>[]{Drops.Drop.class, long.class, boolean.class}, new Drops.Drop("Null Atom", 0, 1), 2_091L, true);
        Object announced = slainWithAtoms("Riftborn", 2_100L);
        equal(takePrevious(), announced);
        settle(announced, 0, observed(value(announced, "atomReward"), "Riftborn", "Null Atom", world, 49), ledger);
        equal(ledger.drops.get("Null Atom"), 9);
        equal(ledger.drops.get("Riftstring"), 1);
        equal(observed(value(announced, "atomReward"), "Riftborn", "Null Atom", new Object(), 49), 0);

        equal(call("atomItem", new Class<?>[]{String.class}, "Riftborn"), "Null Atom");
        equal(call("atomItem", new Class<?>[]{String.class}, "Voidgloom"), "Null Atom");
        equal(call("atomItem", new Class<?>[]{String.class}, "Revenant"), null);
        reset(null);
        setStatic("atomWindow", null);
    }

    private static Object slainWithAtoms(String boss, long now) throws Exception {
        Object death = open(boss, now);
        set(death, "dead", true);
        set(death, "place", 1);
        set(death, "reward", call("takeReward", new Class<?>[]{}));
        set(death, "atomReward", call("takeAtomReward", new Class<?>[]{}));
        return death;
    }

    private static void settle(Object death, int observed, int atoms, Ledger ledger) throws Exception {
        call("settle", new Class<?>[]{DEATH, int.class, int.class, Consumer.class, BiConsumer.class, ToIntFunction.class},
                death, observed, atoms, ledger.kill, ledger.payout, (ToIntFunction<String>) item -> 0);
    }

    private static int observed(Object reward, String boss, String item, Object world, int current) throws Exception {
        return (int) call("observedReward", new Class<?>[]{REWARD, String.class, String.class, Object.class, int.class},
                reward, boss, item, world, current);
    }

    private static final class Ledger {
        int kills;
        final String expected;
        final Map<String, Integer> drops = new LinkedHashMap<>();
        final Consumer<String> kill;
        final BiConsumer<String, Drops.Drop> payout;

        Ledger() { this("Riftborn"); }

        Ledger(String boss) {
            expected = boss;
            kill = b -> {
                if (!b.equals(expected)) throw new AssertionError("Unexpected boss: " + b);
                kills++;
            };
            payout = (b, drop) -> {
                if (!b.equals(expected)) throw new AssertionError("Unexpected boss: " + b);
                drops.merge(drop.item(), drop.quantity(), Integer::sum);
            };
        }
    }

    private static void settle(Object death, int observed, Ledger ledger) throws Exception {
        call("settle", new Class<?>[]{DEATH, int.class, Consumer.class, BiConsumer.class, ToIntFunction.class},
                death, observed, ledger.kill, ledger.payout, (ToIntFunction<String>) item -> 0);
    }

    private static int observed(Object reward, Object world, int current) throws Exception {
        return (int) call("observedReward", new Class<?>[]{REWARD, String.class, String.class, Object.class, int.class},
                reward, "Riftborn", "Null Ovoid", world, current);
    }

    private static void spawn(Object world, int baseline, long now) throws Exception {
        call("rewardSpawned", new Class<?>[]{String.class, String.class, int.class, Object.class, long.class},
                "Riftborn", "Null Ovoid", baseline, world, now);
    }

    private static Object slain(long now) throws Exception {
        Object death = open("Riftborn", now);
        set(death, "dead", true);
        set(death, "place", 1);
        set(death, "reward", call("takeReward", new Class<?>[]{}));
        return death;
    }

    private static Object open(String boss, long now) throws Exception {
        return call("open", new Class<?>[]{String.class, long.class, boolean.class}, boss, now, true);
    }

    private static void hold(Drops.Drop drop, long now) throws Exception {
        call("hold", new Class<?>[]{Drops.Drop.class, long.class}, drop, now);
    }

    private static Object takePrevious() throws Exception {
        return call("takeDeadSlayer", new Class<?>[]{});
    }

    private static void reset(Object world) throws Exception {
        setStatic("death", null);
        setStatic("rewardWindow", null);
        setStatic("messageWorld", world);
        ((List<?>) staticValue("held")).clear();
    }

    @SuppressWarnings("unchecked")
    private static void restoreHeld(List<?> previous) throws Exception {
        List<Object> held = (List<Object>) staticValue("held");
        held.clear();
        held.addAll(previous);
    }

    private static Class<?> nested(String name) {
        try { return Class.forName(BossDrops.class.getName() + "$" + name); }
        catch (ClassNotFoundException e) { throw new AssertionError(e); }
    }

    private static Object staticValue(String name) throws Exception {
        Field field = BossDrops.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = BossDrops.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
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

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BossDrops.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
