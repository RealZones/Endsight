package com.endsight.visual;

/** The submit count must not grow with a near-camera projection or GUI resolution. */
public final class BeamGeometryChecks {
    private static int checks;
    public static void main(String[] args) {
        var normal = BeamGeometry.clip(100, 400, 100, 0, 960, 540);
        check(normal != null && normal.x() == 100 && normal.y() == 400 && normal.dy() == -400, "visible shaft unchanged");
        check(normal.segments() == 24, "native rectangles capped at72 per beam");
        var nearCamera = BeamGeometry.clip(100, 1e12, 100, -1e12, 960, 540);
        check(nearCamera != null && nearCamera.length() < 701, "clip before iterating huge near-camera projections");
        check(nearCamera.segments() * 3 <= 72, "projection distance cannot grow submitted rectangles");
        var diagonal = BeamGeometry.clip(-10000, -10000, 10000, 10000, 960, 540);
        check(diagonal != null && diagonal.x() >= -80.001 && diagonal.y() >= -80.001, "diagonal begins inside padded screen");
        check(diagonal.from() > 0 && diagonal.to() < 1, "keep original gradient parameter after clipping");
        check(BeamGeometry.clip(-1000, -1000, -1000, 2000, 960, 540) == null, "offscreen beam skipped");
        check(BeamGeometry.clip(100, 100, 100, 100, 960, 540) == null, "zero shaft skipped");
        check(BeamGeometry.clip(Double.NaN, 0, 100, 100, 960, 540) == null, "invalid projection skipped");
        check(BeamGeometry.clip(0, 0, Double.POSITIVE_INFINITY, 100, 960, 540) == null, "infinite projection skipped");
        check(BeamGeometry.clip(10, 10, 10, 10.5, 960, 540).segments() == 1, "tiny beam remains bounded and visible");
        for (int scale = 1; scale < 20; scale++) {
            var value = BeamGeometry.clip(50, scale * 1000, 50, -scale * 1000, scale * 960, scale * 540);
            check(value != null && value.segments() <= 24, "resolution independent bound");
        }
        System.out.println(checks + " beam geometry checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
