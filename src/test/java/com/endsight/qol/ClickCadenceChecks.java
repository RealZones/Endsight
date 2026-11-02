package com.endsight.qol;

import java.util.Random;

public final class ClickCadenceChecks {
    public static void main(String[] args) {
        firstDelayAndReset();
        independentConsumers();
        liveSettings();
        invalidSettings();
        noCatchUp();
        bounceAndHitch();
        nanosecondClockWrap();
        originalRhythmRegression();
        System.out.println("Click cadence checks passed");
    }

    private static void firstDelayAndReset() {
        ClickCadence cadence = fixed(10, 10);
        check(!cadence.poll(0), "initial physical press is not duplicated");
        check(!cadence.poll(ms(100) - 1), "first synthetic click waits the whole interval");
        check(cadence.poll(ms(100)), "first synthetic click arrives after its interval");
        check(!cadence.poll(ms(100)), "same frame cannot emit twice");
        cadence.reset();
        check(!cadence.poll(ms(10_000)), "reset forgets the overdue interval");
        check(!cadence.poll(ms(10_100) - 1), "new hold gets its own first delay");
        check(cadence.poll(ms(10_100)), "new hold resumes after the full delay");
    }

    private static void independentConsumers() {
        double[] shared = {10, 10};
        ClickCadence manual = new ClickCadence(() -> shared[0], () -> shared[1], new FixedRandom());
        ClickCadence second = new ClickCadence(() -> shared[0], () -> shared[1], new FixedRandom());
        manual.poll(0);
        second.poll(0);
        check(manual.poll(ms(100)), "manual schedule advances");
        check(second.poll(ms(100)), "advancing manual schedule does not consume the second click");
        manual.reset();
        check(second.poll(ms(200)), "resetting manual schedule does not reset the second schedule");
        check(!manual.poll(ms(200)), "reset manual schedule still waits");
    }

    private static void liveSettings() {
        double[] shared = {10, 10};
        ClickCadence cadence = new ClickCadence(() -> shared[0], () -> shared[1], new FixedRandom());
        cadence.poll(0);
        shared[0] = shared[1] = 20;
        check(!cadence.poll(ms(10)), "raising rate replaces stale target without an immediate click");
        check(!cadence.poll(ms(60) - 1), "raised rate still respects a complete new interval");
        check(cadence.poll(ms(60)), "raised rate applies during the hold, before old drift");
        shared[0] = shared[1] = 1;
        check(!cadence.poll(ms(61)), "lowering rate replaces pending fast interval");
        check(!cadence.poll(ms(110)), "old fast deadline cannot survive a lower rate");
        check(!cadence.poll(ms(1_061) - 1), "lower rate waits its full interval");
        check(cadence.poll(ms(1_061)), "lower rate applies without requiring a new hold");
    }

    private static void invalidSettings() {
        checkFirstInterval(100, Double.POSITIVE_INFINITY, ms(50), "oversized CPS clamps to slider maximum");
        checkFirstInterval(-100, Double.NEGATIVE_INFINITY, ms(1_000), "nonpositive CPS clamps to slider minimum");
        checkFirstInterval(Double.NaN, Double.NaN, 133_333_333L, "NaN falls back to finite default bounds");
        checkFirstInterval(20, 10, 66_666_666L, "reversed bounds still produce a bounded target");
    }

    private static void checkFirstInterval(double min, double max, long interval, String name) {
        ClickCadence cadence = fixed(min, max);
        check(!cadence.poll(0), name + ": arms");
        check(!cadence.poll(interval - 1), name + ": waits");
        check(cadence.poll(interval), name + ": due");
    }

    private static void noCatchUp() {
        ClickCadence cadence = fixed(10, 10);
        cadence.poll(0);
        check(cadence.poll(ms(60_000)), "late frame emits one click");
        for (int i = 0; i < 100; i++) check(!cadence.poll(ms(60_000)), "late frame never replays backlog");
        check(!cadence.poll(ms(60_100) - 1), "next click is scheduled from actual late frame");
        check(cadence.poll(ms(60_100)), "normal schedule resumes after idle");
    }

    private static void bounceAndHitch() {
        ClickCadence bounce = new ClickCadence(() -> 10, () -> 10,
                new FixedRandom(0.5, 0.03, 0.5));
        bounce.poll(0);
        check(!bounce.poll(ms(70) - 1), "bounce preserves 55-85 ms formula");
        check(bounce.poll(ms(70)), "bounce lands at 70 ms for a midpoint sample");
        ClickCadence hitch = new ClickCadence(() -> 10, () -> 10,
                new FixedRandom(0.5, 0.001, 0.5));
        hitch.poll(0);
        check(!hitch.poll(ms(325) - 1), "hitch preserves base plus 150-300 ms formula");
        check(hitch.poll(ms(325)), "hitch lands at 325 ms for a midpoint sample");
    }

    private static void nanosecondClockWrap() {
        ClickCadence cadence = fixed(10, 10);
        long start = Long.MAX_VALUE - ms(50);
        check(!cadence.poll(start), "can start near signed nanosecond clock wrap");
        check(!cadence.poll(start + ms(100) - 1), "wrapped deadline does not fire early");
        check(cadence.poll(start + ms(100)), "wrapped deadline fires on time");
    }

    private static void originalRhythmRegression() {
        for (long seed : new long[]{7, 42, 8_913}) {
            ClickCadence extracted = new ClickCadence(() -> 6, () -> 9, new Random(seed));
            OriginalCadence original = new OriginalCadence(seed);
            for (long now = 0; now <= ms(120_000); now += ms(1)) {
                check(extracted.poll(now) == original.poll(now),
                        "extraction preserves click timings for seed " + seed + " at " + now);
            }
            check(original.drifts > 0 && original.bounces > 0 && original.hitches > 0,
                    "regression exercises drift, bounce, and hitch for seed " + seed);
        }
    }

    private static ClickCadence fixed(double min, double max) {
        return new ClickCadence(() -> min, () -> max, new FixedRandom());
    }

    private static long ms(long value) {
        return value * 1_000_000;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    /** Hand-picked random branches expose timing boundaries without inspecting internal state. */
    private static final class FixedRandom extends Random {
        private final double[] samples;
        private int position;

        FixedRandom(double... samples) {
            this.samples = samples;
        }

        @Override public double nextDouble() {
            return position < samples.length ? samples[position++] : 0.5;
        }

        @Override public int nextInt(int bound) {
            return 0;
        }

        @Override public double nextGaussian() {
            return 0;
        }
    }

    /** The pre-extraction algorithm provides a timing oracle for unchanged valid settings. */
    private static final class OriginalCadence {
        private final Random rng;
        private boolean held;
        private double target;
        private long nextAt, driftAt;
        private int drifts, bounces, hitches;

        OriginalCadence(long seed) {
            rng = new Random(seed);
        }

        boolean poll(long now) {
            if (!held) {
                held = true;
                target = 6 + rng.nextDouble() * 3;
                driftAt = now + ms(800 + rng.nextInt(1800));
                nextAt = now + interval();
                return false;
            }
            if (now >= driftAt) {
                target = Math.max(6, Math.min(9, target + 3 * (0.25 * rng.nextGaussian() - 0.04)));
                driftAt = now + ms(800 + rng.nextInt(1800));
                drifts++;
            }
            if (now < nextAt) return false;
            nextAt = now + interval();
            return true;
        }

        private long interval() {
            double base = 1000.0 / target;
            double r = rng.nextDouble();
            double gap;
            if (r < 0.0067) {
                gap = base + 150 + rng.nextDouble() * 150;
                hitches++;
            } else if (r < 0.05) {
                gap = 55 + rng.nextDouble() * 30;
                bounces++;
            } else {
                gap = base * Math.exp(rng.nextGaussian() * 0.18);
            }
            return (long) (gap * 1_000_000);
        }
    }
}
