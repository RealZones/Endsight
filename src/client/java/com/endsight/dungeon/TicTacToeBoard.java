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

    /**
     * The mark on a taken square's map: the machine's X ({@link TicTacToeLogic#BLUE}) or
     * your O ({@link TicTacToeLogic#RED}), or UNKNOWN for a blank map or one whose pixels
     * have not arrived.
     *
     * Dragsim's new board (2026-11-09 clip) is Hypixel's: red X and O drawn on maps in
     * item frames. X's strokes cross in the middle of the map and O's ring leaves it bare,
     * so the middle says which: it is ink for an X and paper for an O.
     *
     * Ink, not "anything but the background". The first version compared the middle with
     * the commonest colour on the map, exact byte for byte, and in a real room the hint
     * died as soon as your first O went down (17:07, 17:20 and 17:51 clips), while the
     * board-read log stayed silent, i.e. every square read as something. An O read as an
     * X does exactly that: three X and no O is not a board you can be asked to move on.
     * A map image converted to map colours keeps paper in more than one shade, so the
     * paper inside the ring need not be the byte the outside is. Asking whether the
     * middle is red ink does not care how many shades of paper there are.
     */
    static int mark(byte[] colors, java.util.function.IntPredicate ink) {
        if (colors == null || colors.length != 128 * 128) return UNKNOWN;
        boolean drawn = false;
        for (int z = 0; z < 128 && !drawn; z += 8) for (int x = 0; x < 128; x += 8) {
            if (ink.test(colors[x + z * 128] & 0xFF)) { drawn = true; break; }
        }
        if (!drawn) return UNKNOWN;                                  // blank, or not arrived yet
        int middle = 0;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            if (ink.test(colors[64 + dx + (64 + dz) * 128] & 0xFF)) middle++;
        }
        return middle >= 5 ? TicTacToeLogic.BLUE : TicTacToeLogic.RED;
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
