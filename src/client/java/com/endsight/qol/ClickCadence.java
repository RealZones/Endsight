package com.endsight.qol;

import java.util.Objects;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * A click schedule independent of Minecraft and the feature's enabled state.
 * Poll once per frame using a monotonic nanosecond clock. The first poll only starts the
 * schedule: the caller owns the initial press. Reset whenever that run of clicking ends.
 */
public final class ClickCadence {
    private static final double MIN_CPS = 1;
    private static final double MAX_CPS = 20;

    private final DoubleSupplier minCps;
    private final DoubleSupplier maxCps;
    private final Random rng;
    private boolean started;
    private double target;
    private long nextAt;
    private long driftAt;

    public ClickCadence(DoubleSupplier minCps, DoubleSupplier maxCps) {
        this(minCps, maxCps, new Random());
    }

    /** An injected generator makes the real cadence reproducible in offline checks. */
    public ClickCadence(DoubleSupplier minCps, DoubleSupplier maxCps, Random rng) {
        this.minCps = Objects.requireNonNull(minCps);
        this.maxCps = Objects.requireNonNull(maxCps);
        this.rng = Objects.requireNonNull(rng);
    }

    /**
     * Returns at most one click. A late frame schedules from now, so returning from a
     * pause never replays a backlog of overdue clicks.
     */
    public boolean poll(long nowNanos) {
        double min = boundedCps(minCps.getAsDouble(), 6);
        double max = boundedCps(maxCps.getAsDouble(), 9);
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (!started) {
            started = true;
            target = min + rng.nextDouble() * (max - min);
            driftAt = nowNanos + ms(800 + rng.nextInt(1800));
            nextAt = nowNanos + interval();
            return false;
        }

        double boundedTarget = Math.max(min, Math.min(max, target));
        if (boundedTarget != target) {
            // Previously a slider edit could leave the hand outside the new range
            // until its next drift. Replace that stale gap as well as the target.
            target = boundedTarget;
            nextAt = nowNanos + interval();
        }
        if (nowNanos - driftAt >= 0) {
            double span = max - min;
            target = Math.max(min, Math.min(max, target + span * (0.25 * rng.nextGaussian() - 0.04)));
            driftAt = nowNanos + ms(800 + rng.nextInt(1800));
        }
        if (nowNanos - nextAt < 0) return false;
        nextAt = nowNanos + interval();
        return true;
    }

    /** The next poll starts a fresh hold and waits its first interval again. */
    public void reset() {
        started = false;
    }

    private long interval() {
        double base = 1000.0 / target;
        double r = rng.nextDouble();
        double gap;
        if (r < 0.0067) gap = base + 150 + rng.nextDouble() * 150;
        else if (r < 0.05) gap = 55 + rng.nextDouble() * 30;
        else gap = base * Math.exp(rng.nextGaussian() * 0.18);
        return ms(gap);
    }

    private static double boundedCps(double value, double fallback) {
        return Double.isNaN(value) ? fallback : Math.max(MIN_CPS, Math.min(MAX_CPS, value));
    }

    private static long ms(double value) {
        // Even an interval rounded below one nanosecond must not be due again in
        // the same frame; normal samples retain their original conversion exactly.
        return Math.max(1, (long) (value * 1_000_000));
    }
}
