package com.endsight.slayers;

/** Geometry of the invisible guardian/stand pairs used by radiation, not normal guardian attacks. */
final class RadiationBeams {
    private RadiationBeams() {
    }

    record Point(double x, double y, double z) {
        boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }

    record Candidate(boolean active, boolean hiddenGuardian, boolean standTarget, boolean hiddenTarget,
                     Point from, Point to) {
    }

    record Line(Point from, Point to) {
    }

    static Line match(boolean ownRadiation, Point boss, Candidate candidate) {
        if (!ownRadiation || boss == null || !boss.finite() || candidate == null
                || !candidate.active || !candidate.hiddenGuardian || !candidate.standTarget || !candidate.hiddenTarget
                || candidate.from == null || candidate.to == null || !candidate.from.finite() || !candidate.to.finite()) return null;
        Point from = candidate.from, to = candidate.to;
        // Four rotating spokes are stacked at three heights, originating on the boss
        // and ending sixteen blocks away. A nearby guardian alone is not a beam.
        double bx = from.x - boss.x, bz = from.z - boss.z, height = from.y - boss.y;
        if (bx * bx + bz * bz > 0.75 * 0.75 || height < 0 || height > 4.25) return null;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 15 || length > 17 || Math.abs(dy) > 0.25) return null;
        // Vanilla's visible beam extends one block beyond the target's midpoint.
        return new Line(from, new Point(to.x + dx / length, to.y + dy / length, to.z + dz / length));
    }
}
