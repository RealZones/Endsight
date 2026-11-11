package com.endsight.dungeon;

import java.util.Arrays;
import java.util.List;

/**
 * The arrow device in the Goldor phase: a five-by-five wall of item frames, each arrow to be
 * turned to the direction its route needs. The nine routes, indexed (z * 5 + y); -1 is a cell
 * with no frame. Checked against the board Dragsim built in the 2026-11-09 recording, which
 * fits route 4 and no other.
 */
final class ArrowAlignLogic {
    private ArrowAlignLogic() { }

    record Frame(int y, int z, int rotation) { }
    record Board(int originY, int originZ, int[] targets) {
        int targetAt(int y, int z) {
            int row = y - originY, col = z - originZ;
            return row < 0 || row >= 5 || col < 0 || col >= 5 ? -1 : targets[col * 5 + row];
        }
    }

    private static final List<int[]> ROUTES = List.of(
            route(7,7,-1,-1,-1, 1,-1,-1,-1,-1, 1,3,3,3,3, -1,-1,-1,-1,1, -1,-1,-1,7,1),
            route(-1,-1,7,7,5, -1,7,1,-1,5, -1,-1,-1,-1,-1, -1,7,5,-1,1, -1,-1,7,7,1),
            route(7,7,-1,-1,-1, 1,-1,-1,-1,-1, 1,3,-1,7,5, -1,-1,-1,-1,5, -1,-1,-1,3,3),
            route(5,3,3,3,-1, 5,-1,-1,-1,-1, 7,7,-1,-1,-1, 1,-1,-1,-1,-1, 1,3,3,3,-1),
            route(5,3,3,3,3, 5,-1,-1,-1,1, 7,7,-1,-1,1, -1,-1,-1,-1,1, -1,7,7,7,1),
            route(7,7,7,7,-1, 1,-1,-1,-1,-1, 1,3,3,3,3, -1,-1,-1,-1,1, -1,7,7,7,1),
            route(-1,-1,-1,-1,-1, 1,-1,1,-1,1, 1,-1,1,-1,1, 1,-1,1,-1,1, -1,-1,-1,-1,-1),
            route(-1,-1,-1,-1,-1, 1,3,3,3,3, -1,-1,-1,-1,1, 7,7,7,7,1, -1,-1,-1,-1,-1),
            route(-1,-1,-1,-1,-1, -1,1,-1,1,-1, 7,1,7,1,3, 1,-1,1,-1,1, -1,-1,-1,-1,-1));

    /** The route whose frame layout matches this one exactly, or null. */
    static int[] solve(int[] rotations) {
        if (rotations == null || rotations.length != 25) return null;
        for (int[] route : ROUTES) {
            boolean matches = true;
            for (int i = 0; i < 25; i++) {
                if ((route[i] == -1) != (rotations[i] == -1) || rotations[i] < -1 || rotations[i] > 7) { matches = false; break; }
            }
            if (matches) return Arrays.copyOf(route, route.length);
        }
        return null;
    }

    /** The board these frames make, wherever it was built; null unless exactly one route fits. */
    static Board locate(List<Frame> frames) {
        if (frames == null || frames.size() < 8 || frames.size() > 25) return null;
        int minY = frames.stream().mapToInt(Frame::y).min().orElse(0);
        int minZ = frames.stream().mapToInt(Frame::z).min().orElse(0);
        Board found = null;
        for (int y = minY - 4; y <= minY; y++) for (int z = minZ - 4; z <= minZ; z++) {
            int[] rotations = new int[25];
            Arrays.fill(rotations, -1);
            boolean fits = true;
            for (Frame frame : frames) {
                int row = frame.y() - y, col = frame.z() - z;
                if (row < 0 || row >= 5 || col < 0 || col >= 5 || rotations[col * 5 + row] != -1) { fits = false; break; }
                rotations[col * 5 + row] = frame.rotation();
            }
            if (!fits) continue;
            int[] targets = solve(rotations);
            if (targets == null) continue;
            if (found != null) return null;
            found = new Board(y, z, targets);
        }
        return found;
    }

    /** Clicks from one rotation to another: an item frame turns one way only. */
    static int clicks(int current, int target) {
        return current < 0 || target < 0 ? 0 : Math.floorMod(target - current, 8);
    }

    /**
     * One frame's clicks against what the server says. A click turns the arrow on the server a
     * ping later, so every click is counted at once and the server's rotation, when it comes,
     * is checked against the clicks still on their way. That is what keeps the number right
     * while you click faster than your ping, and what lets a click past the last one needed
     * be stopped. A rotation the clicks on their way cannot explain (a teammate turned it)
     * wins; so does the server when nothing comes back for RESYNC_NS, long on purpose,
     * because giving up on a click that was only slow would ask for it again.
     */
    static final class Count {
        static final long RESYNC_NS = 900_000_000L;
        private int observed, predicted, inFlight;
        private long sentAt;

        Count(int rotation) { observed = predicted = rotation; }

        void server(int actual, long now) {
            if (actual != observed) {
                observed = actual;
                int waiting = Math.floorMod(predicted - actual, 8);
                if (waiting <= inFlight) inFlight = waiting;
                else { predicted = actual; inFlight = 0; }
            }
            if (inFlight > 0 && now - sentAt > RESYNC_NS) { predicted = actual; inFlight = 0; }
        }

        void sent(long now) {
            predicted = (predicted + 1) % 8;
            inFlight++;
            sentAt = now;
        }

        int remaining(int target) { return clicks(predicted, target); }
    }

    private static int[] route(int... values) {
        if (values.length != 25) throw new IllegalArgumentException("Route must have 25 cells");
        return values;
    }
}
