package com.endsight.slayers;

import java.util.concurrent.TimeUnit;

public final class SlayerSpawnSignalChecks {
    private static int checks;

    public static void main(String[] args) {
        SlayerSpawnSignal signal = new SlayerSpawnSignal();
        long warning = TimeUnit.SECONDS.toNanos(10);
        signal.arm(warning);
        addBurst(signal, warning, 10.5, 64, -4.5, 8);
        SlayerSpawnSignal.Position position = signal.estimate();
        yes(position != null, "complete burst was ignored");
        near(position.x(), 10.5, 0.3);
        near(position.y(), 64, 0.01);
        near(position.z(), -4.5, 0.3);

        signal.arm(warning + TimeUnit.SECONDS.toNanos(2));
        yes(signal.estimate() == null, "previous spawn leaked into next warning");
        for (int i = 0; i < 24; i++) {
            signal.observe(warning + TimeUnit.SECONDS.toNanos(2), "minecraft:enchant",
                    10.5 + i % 3 * 0.1, 65, -4.5);
        }
        yes(signal.estimate() == null, "single particle type produced a marker");

        signal.arm(warning + TimeUnit.SECONDS.toNanos(4));
        addBurst(signal, warning + TimeUnit.SECONDS.toNanos(4), 10.5, 64, -4.5, 2);
        yes(signal.estimate() == null, "undersized burst produced a marker");
        addBurst(signal, warning + TimeUnit.SECONDS.toNanos(4), 30.5, 70, 8.5, 8);
        position = signal.estimate();
        yes(position != null, "larger cluster was ignored");
        near(position.x(), 30.5, 0.3);
        near(position.z(), 8.5, 0.3);

        signal.arm(warning + TimeUnit.SECONDS.toNanos(6));
        addBurst(signal, warning + TimeUnit.SECONDS.toNanos(6)
                + TimeUnit.MILLISECONDS.toNanos(200), 10.5, 64, -4.5, 8);
        yes(signal.estimate() == null, "late ambient particles produced a marker");
        System.out.println("Slayer spawn signal checks passed: " + checks);
    }

    private static void addBurst(SlayerSpawnSignal signal, long at,
                                 double x, double groundY, double z, int perType) {
        String[] types = {"minecraft:enchant", "minecraft:portal", "minecraft:witch"};
        for (String type : types) {
            for (int i = 0; i < perType; i++) {
                double dx = (i % 4 - 1.5) * 0.24;
                double dz = (i / 4 - 0.5) * 0.22;
                signal.observe(at + TimeUnit.MILLISECONDS.toNanos(i), type,
                        x + dx, groundY + 1 + (i % 3 - 1) * 0.2, z + dz);
            }
        }
    }

    private static void yes(boolean value, String failure) {
        checks++;
        if (!value) throw new AssertionError(failure);
    }

    private static void near(double actual, double expected, double tolerance) {
        checks++;
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError("expected " + expected + " +/- " + tolerance + ", got " + actual);
        }
    }
}
