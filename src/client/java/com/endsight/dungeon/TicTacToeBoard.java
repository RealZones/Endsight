package com.endsight.dungeon;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** The board's geometry is separate from Minecraft state and the minimax move policy. */
final class TicTacToeBoard {
    static final int UNKNOWN = -1;

    private TicTacToeBoard() { }

    record Point(int x, int y, int z) {
        Point add(int dx, int dy, int dz) { return new Point(x + dx, y + dy, z + dz); }
    }

    enum Plane {
        // Floor indexes use a stable world orientation, not the player's changing camera.
        FLOOR(0, 1, 0, 1, 0, 0, 0, 0, 1),
        NORTH(0, 0, -1, -1, 0, 0, 0, -1, 0),
        EAST(1, 0, 0, 0, 0, -1, 0, -1, 0),
        SOUTH(0, 0, 1, 1, 0, 0, 0, -1, 0),
        WEST(-1, 0, 0, 0, 0, 1, 0, -1, 0);

        final int nx, ny, nz, cx, cy, cz, rx, ry, rz;
        Plane(int nx, int ny, int nz, int cx, int cy, int cz, int rx, int ry, int rz) {
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.rx = rx; this.ry = ry; this.rz = rz;
        }
    }

    record Grid(Point origin, Plane plane) {
        Point support(int index) {
            if (index < 0 || index > 8) throw new IllegalArgumentException("Cell outside board");
            int row = index / 3, column = index % 3;
            return origin.add(column * plane.cx + row * plane.rx,
                    column * plane.cy + row * plane.ry, column * plane.cz + row * plane.rz);
        }
        Point button(int index) {
            return support(index).add(plane.nx, plane.ny, plane.nz);
        }
    }

    record Seed(Point button, Plane plane) { }

    record Snapshot(Grid grid, List<Integer> cells) {
        Snapshot { cells = List.copyOf(cells); }
        int[] values() { return cells.stream().mapToInt(Integer::intValue).toArray(); }
    }

    /** Returns UNKNOWN for unloaded cells, unsupported materials or mismatched buttons. */
    interface Probe { int cell(Point support, Plane plane); }

    static Snapshot read(Grid grid, Probe probe) {
        var cells = new java.util.ArrayList<Integer>(9);
        for (int index = 0; index < 9; index++) {
            int value = probe.cell(grid.support(index), grid.plane());
            if (value < 0 || value > 2) return null;
            cells.add(value);
        }
        return new Snapshot(grid, cells);
    }

    /** Render-time validation must reject partial packets without allocating a board every frame. */
    static boolean matches(Snapshot snapshot, Probe probe) {
        Grid grid = snapshot.grid();
        for (int index = 0; index < 9; index++) {
            if (probe.cell(grid.support(index), grid.plane()) != snapshot.cells().get(index)) return false;
        }
        return true;
    }

    /** A remaining empty button can be anywhere in the 3x3 board after earlier moves. */
    static Snapshot find(List<Seed> seeds, Probe probe) {
        Map<Grid, Snapshot> candidates = new LinkedHashMap<>();
        var checked = new HashSet<Grid>();
        for (Seed seed : seeds) {
            Plane plane = seed.plane();
            Point support = seed.button().add(-plane.nx, -plane.ny, -plane.nz);
            for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) {
                Point origin = support.add(-column * plane.cx - row * plane.rx,
                        -column * plane.cy - row * plane.ry, -column * plane.cz - row * plane.rz);
                Grid grid = new Grid(origin, plane);
                if (!checked.add(grid)) continue;
                Snapshot snapshot = read(grid, probe);
                if (snapshot != null) candidates.put(grid, snapshot);
            }
        }
        return candidates.size() == 1 ? candidates.values().iterator().next() : null;
    }

    /** Never keep the previous hint while packets are replacing a button and its tile. */
    static final class Stabilizer {
        private Snapshot previous, stable;
        void clear() { previous = null; stable = null; }
        Snapshot stable() { return stable; }
        void observe(Snapshot current) {
            stable = current != null && current.equals(previous) ? current : null;
            previous = current;
        }
    }
}
