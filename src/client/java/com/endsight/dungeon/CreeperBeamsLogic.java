package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Creeper Beams: "Shoot the sea lanterns to link beams through the creeper!" A hit lantern
 * turns to prismarine; when its partner is hit the beam between them is linked, and a
 * lone hit turns back to a sea lantern a few seconds later.
 *
 * A run on 2026-11-09 saw two lanterns light in the same tick, (-57 74 -153)
 * and (-59 84 -153), and three more lighting alone. Every one of them mirrors through the
 * same point, about (-57.5 79.5 -152.5) in mid-air above the reward chest: that is where
 * the creeper floats, and it is why a beam "through the creeper" means a pair whose middle
 * is the creeper. Decoration lanterns in the walls line up with nothing.
 */
final class CreeperBeamsLogic {
    record Point(double x, double y, double z) {
        Point minus(Point o) { return new Point(x - o.x, y - o.y, z - o.z); }
        double dot(Point o) { return x * o.x + y * o.y + z * o.z; }
        Point mid(Point o) { return new Point((x + o.x) / 2, (y + o.y) / 2, (z + o.z) / 2); }
        double distance(Point o) { Point d = minus(o); return Math.sqrt(d.dot(d)); }
    }

    record Pair(int a, int b) { }

    /** How far the beam may pass from the creeper's middle: its box is 0.6 wide and 1.7 tall. */
    static final double REACH = 1.2;

    private CreeperBeamsLogic() { }

    /**
     * The partner of each lantern, closest beams first, every lantern used once. The creeper
     * must sit well inside the beam, not near one end, so a lantern beside the creeper and
     * any other lantern behind it is not a pair.
     */
    static List<Pair> pairs(List<Point> lanterns, Point creeper) {
        record Candidate(double miss, int a, int b) { }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < lanterns.size(); i++) {
            for (int j = i + 1; j < lanterns.size(); j++) {
                Point a = lanterns.get(i), b = lanterns.get(j);
                Point ab = b.minus(a);
                double length2 = ab.dot(ab);
                if (length2 < 4) continue;
                double t = creeper.minus(a).dot(ab) / length2;
                if (t < .25 || t > .75) continue;
                Point nearest = new Point(a.x() + ab.x() * t, a.y() + ab.y() * t, a.z() + ab.z() * t);
                double miss = nearest.distance(creeper);
                if (miss <= REACH) candidates.add(new Candidate(miss, i, j));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::miss));
        boolean[] used = new boolean[lanterns.size()];
        List<Pair> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (used[c.a()] || used[c.b()]) continue;
            used[c.a()] = used[c.b()] = true;
            out.add(new Pair(c.a(), c.b()));
        }
        return out;
    }
}
