package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Glides map markers between server updates without delaying or inventing room pixels.
 *
 * The server moves a marker in half-pixel steps, a few times a second. This used to chase
 * each step exponentially, which arrives fast and then waits for the next one: stop, go,
 * stop, go - the jitter Fear saw. Now each step is spread over the time the previous one
 * took, so a walking player moves at an even speed, about one update behind.
 */
final class DungeonMapMotion {
    /** Where the server puts a marker; angle in degrees. */
    record Target(String name, boolean self, double x, double y, double angle) { }
    record Marker(String name, boolean self, double x, double y, double angle) { }

    private static final long MIN_NS = 50_000_000L, MAX_NS = 500_000_000L;
    /** Further than this in one update is a teleport (a warp, a fresh map), not a walk. */
    private static final double SNAP = 20;

    private static final class Track {
        String name;
        boolean self;
        double fromX, fromY, fromAngle, toX, toY, toAngle;
        long start, changed, duration;

        Track(Target target, long now) {
            name = target.name(); self = target.self();
            fromX = toX = target.x(); fromY = toY = target.y(); fromAngle = toAngle = target.angle();
            start = changed = now;
        }

        double progress(long now) {
            return duration <= 0 ? 1 : Math.clamp((now - start) / (double) duration, 0, 1);
        }
        double x(long now) { return fromX + (toX - fromX) * progress(now); }
        double y(long now) { return fromY + (toY - fromY) * progress(now); }
        double angle(long now) { return fromAngle + (toAngle - fromAngle) * progress(now); }

        void aim(Target target, long now) {
            name = target.name(); self = target.self();
            // Keep the unwrapped angle, so 350 to 10 turns 20 degrees, not 340 back round.
            double turn = ((target.angle() - toAngle) % 360 + 540) % 360 - 180;
            boolean moved = target.x() != toX || target.y() != toY;
            if (!moved && Math.abs(turn) < 1e-6) return;
            double x = x(now), y = y(now), angle = angle(now);
            if (Math.hypot(target.x() - x, target.y() - y) > SNAP) {
                fromX = toX = target.x(); fromY = toY = target.y(); fromAngle = toAngle = toAngle + turn;
                duration = 0;
            } else {
                fromX = x; fromY = y; fromAngle = angle;
                toX = target.x(); toY = target.y(); toAngle += turn;
                duration = Math.clamp(now - changed, MIN_NS, MAX_NS);
            }
            start = changed = now;
        }
    }

    private final List<Track> tracks = new ArrayList<>();

    void clear() { tracks.clear(); }

    List<Marker> update(List<Target> targets, long now) {
        List<Track> next = new ArrayList<>();
        Set<Track> used = new HashSet<>();
        for (Target target : targets) {
            Track match = null;
            double nearest = 12 * 12;
            for (Track track : tracks) {
                if (used.contains(track)) continue;
                if (target.name() != null && target.name().equalsIgnoreCase(track.name)) {
                    match = track; break;
                }
                if (target.name() != null || track.name != null || target.self() != track.self) continue;
                double distance = Math.pow(track.toX - target.x(), 2) + Math.pow(track.toY - target.y(), 2);
                if (distance < nearest) { nearest = distance; match = track; }
            }
            if (match == null) match = new Track(target, now);
            else match.aim(target, now);
            used.add(match);
            next.add(match);
        }
        tracks.clear(); tracks.addAll(next);
        return tracks.stream().map(t -> new Marker(t.name, t.self, t.x(now), t.y(now), t.angle(now))).toList();
    }
}
