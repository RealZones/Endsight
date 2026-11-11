package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Geometry and sequence state for the in-world 4x4 button board. */
final class SimonSaysBoard {
    private SimonSaysBoard() { }

    record Point(int x, int y, int z) {
        Point add(int dx, int dy, int dz) { return new Point(x + dx, y + dy, z + dz); }
    }

    enum Facing {
        NORTH(0, -1, -1, 0), EAST(1, 0, 0, -1),
        SOUTH(0, 1, 1, 0), WEST(-1, 0, 0, 1);

        final int nx, nz, rightX, rightZ;
        Facing(int nx, int nz, int rightX, int rightZ) {
            this.nx = nx; this.nz = nz; this.rightX = rightX; this.rightZ = rightZ;
        }
    }

    record Grid(Point topLeft, Facing facing) {
        Point support(int index) {
            return topLeft.add(index % 4 * facing.rightX, -index / 4, index % 4 * facing.rightZ);
        }
        Point button(int index) {
            Point support = support(index);
            return support.add(facing.nx, 0, facing.nz);
        }
    }

    record Seed(Point button, Facing facing) { }
    record Snapshot(Grid grid, int lights, int pressed) { }

    /** -1 is a missing/mismatched cell; bit 0 means lamp, bit 1 pressed. */
    interface Probe { int cell(Point support, Facing facing); }

    static Snapshot read(Grid grid, Probe probe) {
        int lights = 0, pressed = 0;
        for (int index = 0; index < 16; index++) {
            int cell = probe.cell(grid.support(index), grid.facing());
            if (cell < 0 || cell > 3) return null;
            if ((cell & 1) != 0) lights |= 1 << index;
            if ((cell & 2) != 0) pressed |= 1 << index;
        }
        return new Snapshot(grid, lights, pressed);
    }

    static Snapshot find(List<Seed> seeds, Probe probe) {
        Set<Grid> tried = new HashSet<>();
        Snapshot match = null;
        for (Seed seed : seeds) {
            Point support = seed.button().add(-seed.facing().nx, 0, -seed.facing().nz);
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
                Facing facing = seed.facing();
                Grid grid = new Grid(support.add(-column * facing.rightX, row,
                        -column * facing.rightZ), facing);
                if (!tried.add(grid)) continue;
                Snapshot candidate = read(grid, probe);
                if (candidate == null) continue;
                if (match != null) return null;
                match = candidate;
            }
        }
        return match;
    }

    /**
     * What the board has flashed, and how far into it the presses have got.
     *
     * Dragsim's board replays the whole sequence every round: A; then A, B; then A, B, C
     * (the 2026-11-09 recording). So what a round flashes is the sequence, whole. This
     * once also allowed for a board that flashes only the new tile, and added a lone
     * flash onto what it knew. That broke on a restart: a teammate's wrong press is never
     * seen here - Dragsim sends no button change for anyone's press, only our own client
     * shows ours - so the board restarting with one flash was taken as the next tile, and
     * the hints asked for three buttons where the board wanted one. Fear hit it in a run.
     *
     * A round that grows what was known by its next tile is taken at once. Anything else
     * - shorter, or different - is a restart, but only once the board has been dark for
     * RESTART_QUIET ticks: a replay can pause between its lamps (240 ms was the longest
     * gap recorded) and a half-seen replay must not be taken for a new sequence.
     */
    static final class Sequence {
        private enum Phase { WATCH, INPUT, NEXT }
        /** Dark ticks before a round is read: 350 ms, past every gap inside a replay seen. */
        private static final int SETTLE_QUIET = 7;
        /** Dark ticks before a round that is not the next one is read as a restart. */
        private static final int RESTART_QUIET = 12;
        private final List<Integer> known = new ArrayList<>();
        private final List<Integer> shown = new ArrayList<>();
        private Phase phase = Phase.WATCH;
        private int priorLights, priorPressed, quietTicks, inputIndex, darkTicks;

        void reset() {
            known.clear(); shown.clear(); phase = Phase.WATCH;
            priorLights = priorPressed = quietTicks = inputIndex = darkTicks = 0;
        }

        int target() {
            return phase == Phase.INPUT && inputIndex < known.size() ? known.get(inputIndex) : -1;
        }

        int count() { return known.size(); }
        int step() { return inputIndex; }

        void observe(int lights, int pressed) {
            int freshLights = lights & ~priorLights;
            int freshPresses = pressed & ~priorPressed;
            priorLights = lights;
            priorPressed = pressed;
            int wasDark = darkTicks;
            darkTicks = lights == 0 ? darkTicks + 1 : 0;

            if (freshLights != 0) {
                if (phase != Phase.WATCH) {
                    shown.clear(); phase = Phase.WATCH; inputIndex = 0;
                }
                // A packet exposing several lights at once has no reliable order.
                if (Integer.bitCount(freshLights) != 1) { reset(); return; }
                int lamp = Integer.numberOfTrailingZeros(freshLights);
                // Dragsim resends the whole board every tick, every lamp dark and then the
                // lit one. Should one tick's batch be split, the lamp reads dark for a tick
                // and lit again: the same flash, not a second one. A real repeat of the
                // same tile has a gap of several ticks between.
                boolean flicker = wasDark <= 1 && !shown.isEmpty() && shown.getLast() == lamp;
                if (!flicker) shown.add(lamp);
                quietTicks = 0;
            } else if (phase == Phase.WATCH && !shown.isEmpty()) {
                // Count darkness, not the time a lamp remains on. Otherwise a
                // long flash could publish the hint after one dark tick.
                quietTicks = lights == 0 ? quietTicks + 1 : 0;
            }

            if (phase == Phase.WATCH && lights == 0 && !shown.isEmpty()) settle();
            if (phase != Phase.INPUT || freshPresses == 0) return;
            if (Integer.bitCount(freshPresses) != 1
                    || Integer.numberOfTrailingZeros(freshPresses) != target()) {
                // A wrong press can restart the server board; wait for its lights.
                known.clear(); shown.clear(); phase = Phase.NEXT; inputIndex = 0;
                return;
            }
            if (++inputIndex == known.size()) phase = Phase.NEXT;
        }

        private void settle() {
            if (quietTicks < SETTLE_QUIET) return;
            boolean grows = shown.size() > known.size() && shown.subList(0, known.size()).equals(known);
            // Only the next round is read early. The same round again, or a shorter one, may
            // still be a replay that has paused before its newest lamp.
            if (!grows && quietTicks < RESTART_QUIET) return;
            // The next round, the same round again after a slip, or a restart: in every
            // case what the board just flashed is the whole sequence.
            known.clear(); known.addAll(shown);
            shown.clear(); quietTicks = 0; inputIndex = 0; phase = Phase.INPUT;
        }
    }
}
