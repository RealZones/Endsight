package com.endsight.slayers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The three-particle burst at a Revenant's future position, measured from Crypts spawns. */
final class SlayerSpawnSignal {
    private static final long WINDOW_NS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final int MAX_SAMPLES = 384;
    private static final double RADIUS_SQR = 2.3 * 2.3;

    record Position(double x, double y, double z, int samples) { }
    private record Sample(int type, double x, double y, double z) { }

    private final List<Sample> samples = new ArrayList<>();
    private long warningNs;

    void arm(long warningNs) {
        this.warningNs = warningNs;
        samples.clear();
    }

    void observe(long at, String type, double x, double y, double z) {
        long offset = at - warningNs;
        if (warningNs == 0 || offset < 0 || offset > WINDOW_NS || samples.size() >= MAX_SAMPLES) return;
        int id = switch (type) {
            case "minecraft:enchant" -> 0;
            case "minecraft:portal" -> 1;
            case "minecraft:witch" -> 2;
            default -> -1;
        };
        if (id >= 0) samples.add(new Sample(id, x, y, z));
    }

    Position estimate() {
        if (samples.size() < 12) return null;
        Position best = null;
        for (Sample center : samples) {
            int[] types = new int[3];
            int count = 0;
            double sx = 0, sy = 0, sz = 0;
            for (Sample sample : samples) {
                double dx = sample.x - center.x;
                double dz = sample.z - center.z;
                if (dx * dx + dz * dz > RADIUS_SQR || Math.abs(sample.y - center.y) > 2.5) continue;
                types[sample.type]++;
                count++;
                sx += sample.x;
                sy += sample.y;
                sz += sample.z;
            }
            if (count < 12 || types[0] < 3 || types[1] < 3 || types[2] < 3) continue;
            if (best == null || count > best.samples) {
                best = new Position(sx / count, Math.round(sy / count - 1.0), sz / count, count);
            }
        }
        return best;
    }
}
