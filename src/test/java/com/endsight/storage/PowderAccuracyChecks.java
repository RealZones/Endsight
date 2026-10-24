package com.endsight.storage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Saved balances and unrelated rewards must never turn one block into a billion-per-hour rate. */
public final class PowderAccuracyChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        savedBalanceNeedsLiveAnchor();
        unchangedFirstAnchor();
        tinyWindowsDoNotTeachRates();
        exactBalanceCorrectsOverestimate();
        spendingPreservesEarnedPowder();
        establishedRatesRejectRewardSpikes();
        newRatesCanLearnLargeGearGains();
        oldCalibrationMigratesOnce();
        relearnKeepsExistingDisplaySteady();
        malformedRatesAreIgnored();
        System.out.println(checks + " powder accuracy checks passed");
    }

    private static void savedBalanceNeedsLiveAnchor() throws Exception {
        fresh();
        read("#calibration\t2", "Amethyst\t400\t24\t1000", "#total\tVoid\t1000\t24");
        mine(3, 100);
        sync(1, 1_000_000_000L);
        near(rates()[3], 400, "offline balance jump taught an amethyst rate");
        near(weights()[3], 1000, "offline balance jump became measurement weight");
        equal(gain(1), 40_000, "first sync counted powder earned before this launch");
        set("activeMs", 60_000L);
        near((double) call("pace", new Class<?>[]{int.class}, 1), 2_400_000, "downtime jump inflated hourly rate");
    }

    private static void unchangedFirstAnchor() throws Exception {
        fresh();
        read("#calibration\t2", "Amethyst\t100\t24\t0", "#total\tVoid\t10000\t24");
        mine(3, 10);
        sync(1, 10_000);
        near(atVisit()[3], 10, "unchanged first total did not close saved interval");
        check(anchors()[1], "unchanged first total did not become a live anchor");
        mine(3, 64);
        sync(1, 16_400);
        near(rates()[3], 100, "next interval included blocks from before first live anchor");
        near(weights()[3], 64, "next interval counted pre-anchor blocks as calibration weight");
    }

    private static void tinyWindowsDoNotTeachRates() throws Exception {
        fresh();
        sync(0, 1000);
        mine(0, 1);
        sync(0, 4030); // Observed local log: 3,030 powder was assigned to one End Stone.
        near(rates()[0], 16.4, "one-block 3030-powder spike poisoned End Stone");
        near(weights()[0], 0, "one-block spike became a trusted sample");
        mine(0, 63);
        sync(0, 5063);
        near(weights()[0], 0, "63-block interval taught a rate");
        mine(0, 64);
        sync(0, 6343);
        near(rates()[0], 20, "64-block live interval failed to learn");
        near(weights()[0], 64, "accepted interval has wrong sample weight");
    }

    private static void exactBalanceCorrectsOverestimate() throws Exception {
        fresh();
        rates()[0] = 40;
        weights()[0] = 1000;
        sync(0, 1000);
        mine(0, 100);
        equal(estimate(0), 5000, "overestimate fixture");
        sync(0, 2000);
        equal(gain(0), 1000, "large downward correction preserved overstated session gain");
        set("activeMs", 60_000L);
        near((double) call("pace", new Class<?>[]{int.class}, 0), 60_000, "hourly rate ignored exact correction");

        fresh();
        rates()[0] = 40;
        sync(0, 1000);
        mine(0, 100);
        sync(0, 1000);
        equal(gain(0), 0, "unchanged live balance preserved unearned block estimates");
        near(atVisit()[0], 100, "zero-gain interval did not close");
    }

    private static void spendingPreservesEarnedPowder() throws Exception {
        fresh();
        rates()[0] = 40;
        sync(0, 1000);
        mine(0, 100);
        sync(0, 500);
        equal(gain(0), 4000, "spending powder was counted as negative mining");
        sync(0, 250);
        equal(gain(0), 4000, "second upgrade changed earned powder");
    }

    private static void establishedRatesRejectRewardSpikes() throws Exception {
        fresh();
        rates()[0] = 40;
        weights()[0] = 1000;
        sync(0, 1000);
        mine(0, 100);
        sync(0, 1_001_000);
        near(rates()[0], 40, "large unrelated reward poisoned an established rate");
        near(weights()[0], 1000, "rejected reward increased sample weight");
        equal(gain(0), 4000, "large reward was counted as mining");
    }

    private static void newRatesCanLearnLargeGearGains() throws Exception {
        fresh();
        sync(1, 1000);
        mine(3, 64);
        sync(1, 321_000);
        near(rates()[3], 5000, "unmeasured amethyst has an arbitrary per-block cap");
        equal(gain(1), 320_000, "first amethyst calibration erased measured mining");

        fresh();
        sync(0, 1000);
        mine(0, 64);
        sync(0, 65_000);
        near(rates()[0], 1000, "fresh seed could not learn larger current gear gains");
        equal(gain(0), 64_000, "fresh seed calibration erased measured mining");
    }

    private static void oldCalibrationMigratesOnce() throws Exception {
        fresh();
        read("End Stone\t3030\t15\t5000", "Amethyst\t999999999\t15\t5000",
                "#total\tEnder\t123456\t15", "#total\tVoid\t987654\t15");
        near(rates()[0], 16.4, "old potentially poisoned rate survived migration");
        near(rates()[3], 0, "old unverified amethyst rate survived migration");
        near(weights()[0], 0, "old calibration weight survived migration");
        equal(estimate(0), 123456, "migration discarded saved Ender balance");
        equal(estimate(1), 987654, "migration discarded saved Void balance");
        check(!anchors()[0] && !anchors()[1], "saved balances became live anchors");
        rates()[3] = 567.25;
        weights()[3] = 256;
        String saved = (String) call("ratesText", new Class<?>[0]);
        check(saved.startsWith("#calibration\t2\n"), "new file has no calibration schema");
        fresh();
        call("readRates", new Class<?>[]{List.class}, saved.lines().toList());
        near(rates()[3], 567.25, "new trusted rate was discarded at restart");
        near(weights()[3], 256, "new trusted sample weight was discarded at restart");
        equal(estimate(1), 987654, "new format lost saved balance");
    }

    private static void relearnKeepsExistingDisplaySteady() throws Exception {
        fresh();
        rates()[0] = 40;
        weights()[0] = 5000;
        sync(0, 1000);
        mine(0, 100);
        long before = estimate(0), beforeGain = gain(0);
        call("clearCalibration", new Class<?>[0]);
        equal(estimate(0), before, "Relearn Rates changed current estimate");
        equal(gain(0), beforeGain, "Relearn Rates changed session gain");
        near(weights()[0], 0, "Relearn Rates retained old measurement weight");
        check(!anchors()[0], "Relearn Rates reused an old menu anchor");
        sync(0, 5000);
        mine(0, 64);
        sync(0, 69_000);
        near(rates()[0], 1000, "Relearn Rates did not allow a larger gear rate");
    }

    private static void malformedRatesAreIgnored() throws Exception {
        fresh();
        read("#calibration\t2", "End Stone\tNaN\t24\t1000", "Obsidian\tInfinity\t24\t1000",
                "Amethyst\t99999999\t24\tInfinity", "#total\tVoid\t123456\t24");
        near(rates()[0], 16.4, "NaN persisted rate accepted");
        near(rates()[1], 41.2, "infinite persisted rate accepted");
        near(rates()[3], 0, "infinite sample weight accepted");
        equal(estimate(1), 123456, "malformed rate row hid valid saved balance");
    }

    private static void fresh() throws Exception {
        for (String name : List.of("blocks", "blocksAtVisit", "blocksAtSession", "rateWeight"))
            Arrays.fill((double[]) field(name).get(null), 0);
        System.arraycopy(new double[]{16.4, 41.2, 139, 0}, 0, rates(), 0, 4);
        Arrays.fill((int[]) field("rateBuff").get(null), 24);
        Arrays.fill((int[]) field("buff").get(null), 24);
        Arrays.fill((long[]) field("total").get(null), -1);
        Arrays.fill((long[]) field("sessionBase").get(null), -1);
        Arrays.fill((boolean[]) field("sessionSet").get(null), false);
        Arrays.fill(anchors(), false);
        set("petKey", "");
        set("activeMs", 0L);
    }

    private static void read(String... lines) throws Exception { call("readRates", new Class<?>[]{List.class}, List.of(lines)); }
    private static void mine(int kind, int count) throws Exception { ((double[]) field("blocks").get(null))[kind] += count; }
    private static void sync(int type, long balance) throws Exception { call("syncVisit", new Class<?>[]{int.class, long.class}, type, balance); }
    private static long estimate(int type) throws Exception { return (long) call("estimate", new Class<?>[]{int.class}, type); }
    private static long gain(int type) throws Exception { return (long) call("gain", new Class<?>[]{int.class}, type); }
    private static double[] rates() throws Exception { return (double[]) field("rate").get(null); }
    private static double[] weights() throws Exception { return (double[]) field("rateWeight").get(null); }
    private static double[] atVisit() throws Exception { return (double[]) field("blocksAtVisit").get(null); }
    private static boolean[] anchors() throws Exception { return (boolean[]) field("liveVisit").get(null); }
    private static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    private static Field field(String name) throws Exception {
        Field f = PowderTracker.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = PowderTracker.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
    private static void equal(long value, long expected, String message) { check(value == expected, message + ": " + value + " != " + expected); }
    private static void near(double value, double expected, String message) { check(Math.abs(value - expected) < .001, message + ": " + value + " != " + expected); }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
