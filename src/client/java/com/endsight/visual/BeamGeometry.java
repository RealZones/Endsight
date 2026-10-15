package com.endsight.visual;

/** Clipped screen geometry keeps the beacon's work bounded even near the camera. */
final class BeamGeometry {
    static final int MAX_SEGMENTS = 24;
    record Visible(double x, double y, double dx, double dy, double from, double to) {
        double length() { return Math.hypot(dx, dy); }
        int segments() { return Math.min(MAX_SEGMENTS, Math.max(1, (int) Math.ceil(length()))); }
    }

    private BeamGeometry() { }

    static Visible clip(double x0, double y0, double x1, double y1, int width, int height) {
        double dx = x1 - x0, dy = y1 - y0;
        if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(dx) || !Double.isFinite(dy)) return null;
        double low = 0, high = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 + 80, width + 80 - x0, y0 + 80, height + 80 - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return null; continue; }
            double t = q[i] / p[i];
            if (p[i] < 0) low = Math.max(low, t); else high = Math.min(high, t);
            if (low > high) return null;
        }
        if (high <= low) return null;
        Visible clipped = new Visible(x0 + dx * low, y0 + dy * low, dx * (high - low), dy * (high - low), low, high);
        return clipped.length() < .001 ? null : clipped;
    }
}
